package com.example.bsa.service

import android.util.Log
import com.example.bsa.engine.RawParsedTransaction
import com.example.bsa.model.AccountMetadata
import com.example.bsa.model.BankTransaction
import com.example.bsa.model.FacilityType
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "FirebaseSmartAssist"

data class SmartClassificationPatch(
    val slNo: Int,
    val category: String,
    val partyName: String,
    val isSelfTransfer: Boolean
)

data class SmartClassificationOutcome(
    val patches: Map<Int, SmartClassificationPatch>,
    val statusNote: String
)

data class SmartLayoutRecoveryResult(
    val metadata: AccountMetadata?,
    val transactions: List<RawParsedTransaction>,
    val statusNote: String
)

/**
 * Cloud-assisted statement layout recovery & ambiguous narration classification backed by
 * Firebase AI Logic (gemini-3.8-flash) with strict JSON Schema output, lazy model initialization,
 * 45-second timeout guard, and transparent error reporting. Zero API keys required.
 */
class FirebaseSmartAssistProvider : OnlineSmartAssistProvider {

    override val isAvailable: Boolean = true
    override val statusNotice: String = "Online Hybrid Smart Assist active (auto-falls back offline)."

    private val classificationSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "items" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "slNo" to Schema.integer(description = "Exact transaction serial number"),
                            "category" to Schema.string(
                                description = "Exact underwriting category such as Home Loan, Loan, Credit Card Payment, OD Interest / Servicing, Cash Withdrawal, Cash Deposit, Interest, Investment Expense, Reversal, Food, Fuel, Travel, Utilities, Medical, Entertainment, Alcohol, Groceries, Household, Clothing, Vehicle, Personal Care, Logistics, Transfer from Self, Transfer To Self, Transfer from <Party>, Transfer to <Party>, or Others"
                            ),
                            "partyName" to Schema.string(description = "Clean counterparty or merchant name"),
                            "isSelfTransfer" to Schema.boolean(description = "True if transfer is between the account holder's own accounts or proprietor trade name")
                        )
                    )
                )
            )
        )
    }

    private val layoutRecoverySchema by lazy {
        Schema.obj(
            properties = mapOf(
                "bankName" to Schema.string(description = "Detected Indian bank name"),
                "holderName" to Schema.string(description = "Account holder full name"),
                "accountNumber" to Schema.string(description = "Account number"),
                "ifscCode" to Schema.string(description = "11-character Indian IFSC code if present"),
                "statementPeriod" to Schema.string(description = "Statement date range"),
                "transactions" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "dateIso" to Schema.string(description = "Transaction date in yyyy-MM-dd format"),
                            "chequeNo" to Schema.string(description = "Cheque or reference number, empty string if none"),
                            "description" to Schema.string(description = "Complete transaction narration"),
                            "signedAmount" to Schema.double(description = "Positive for credit/deposit, negative for debit/withdrawal"),
                            "balance" to Schema.double(description = "Running balance after transaction (negative if Dr/overdrawn, positive if Cr)")
                        ),
                        optionalProperties = listOf("chequeNo")
                    )
                )
            ),
            optionalProperties = listOf("ifscCode", "statementPeriod")
        )
    }

    private val classificationModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = classificationSchema
            }
        )
    }

    private val recoveryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.0f
                responseMimeType = "application/json"
                responseSchema = layoutRecoverySchema
            }
        )
    }

    override suspend fun classifyAmbiguousTransactions(
        holderName: String,
        ambiguousTxns: List<BankTransaction>
    ): SmartClassificationOutcome {
        if (ambiguousTxns.isEmpty()) {
            return SmartClassificationOutcome(emptyMap(), "All transactions matched deterministically.")
        }

        val payloadLines = ambiguousTxns.take(35).joinToString("\n") { t ->
            val dir = if (t.amount >= 0) "CREDIT" else "DEBIT"
            "slNo=${t.slNo} | $dir | INR=${"%.2f".format(t.amount)} | desc=${t.description.replace("\n", " ").take(140)}"
        }

        val prompt = """
            You are an Indian bank statement underwriting classifier.
            Account Holder Name: "$holderName"
            Classify each ambiguous bank transaction below into an exact underwriting category, clean counterparty/merchant name, and whether it is a self-transfer (same person, initials, spelling variation, or proprietor business name).
            Allowed categories: Home Loan, Loan, Credit Card Payment, OD Interest / Servicing, Cash Withdrawal, Cash Deposit, Interest, Investment Expense, Reversal, Food, Fuel, Travel, Utilities, Medical, Entertainment, Alcohol, Groceries, Household, Clothing, Vehicle, Personal Care, Logistics, Transfer from Self, Transfer To Self, Transfer from <Party>, Transfer to <Party>, Others.
            Rules:
            - If isSelfTransfer is true: use "Transfer from Self" for CREDIT, "Transfer To Self" for DEBIT.
            - For business/person transfers: use "Transfer from <Clean Party>" for CREDIT or "Transfer to <Clean Party>" for DEBIT.
            - Keep slNo identical to input.

            Transactions:
            $payloadLines
        """.trimIndent()

        return try {
            val response = withTimeout(45_000L) {
                classificationModel.generateContent(prompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Empty response from cloud classifier.")
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("items")
            val result = linkedMapOf<Int, SmartClassificationPatch>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val slNo = item.optInt("slNo", -1)
                    val cat = item.optString("category", "").trim()
                    val party = item.optString("partyName", "").trim()
                    val self = item.optBoolean("isSelfTransfer", false)
                    if (slNo > 0 && cat.isNotBlank()) {
                        result[slNo] = SmartClassificationPatch(
                            slNo = slNo,
                            category = cat,
                            partyName = party.ifBlank { cat },
                            isSelfTransfer = self || cat.equals("Transfer from Self", true) || cat.equals("Transfer To Self", true)
                        )
                    }
                }
            }
            SmartClassificationOutcome(
                patches = result,
                statusNote = "Cloud Smart Assist refined ${result.size} ambiguous narration(s)."
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Cloud narration classification timed out", e)
            SmartClassificationOutcome(emptyMap(), "Cloud Smart Assist timed out (45s); local deterministic categories retained.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Cloud narration classification failed", e)
            SmartClassificationOutcome(
                emptyMap(),
                "Cloud Smart Assist unavailable (${e.localizedMessage ?: "network/attestation error"}); local deterministic rules used."
            )
        }
    }

    override suspend fun recoverStatementLayout(
        fileName: String,
        rawPdfText: String,
        facilityType: FacilityType
    ): SmartLayoutRecoveryResult {
        if (rawPdfText.isBlank()) {
            return SmartLayoutRecoveryResult(null, emptyList(), "No text available for layout recovery.")
        }

        val clippedText = rawPdfText.take(28_000)
        val prompt = """
            Extract structured bank statement metadata and every transaction row from this Indian bank statement PDF text.
            Facility Type: ${facilityType.label}
            CRITICAL RULES:
            1. Preserve exact transaction order.
            2. signedAmount MUST be positive (+) for deposits/credits and negative (-) for withdrawals/debits.
            3. balance MUST be negative for Dr/overdrawn balances and positive for Cr balances so Prev_Balance + signedAmount == Current_Balance.
            4. dateIso MUST be strictly yyyy-MM-dd.
            5. Merge wrapped multi-line narrations into a single clean description.
            6. Do NOT invent or fabricate rows that are not present in the statement text.

            Statement Text:
            $clippedText
        """.trimIndent()

        return try {
            val response = withTimeout(55_000L) {
                recoveryModel.generateContent(prompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Empty layout recovery response.")
            val root = JSONObject(rawJson)
            val meta = AccountMetadata(
                bankName = root.optString("bankName", "").trim().ifBlank { "Indian Bank" },
                holderName = root.optString("holderName", "").trim().ifBlank { "Account Holder" },
                address = "",
                accountNumber = root.optString("accountNumber", "").trim().ifBlank { "Not Detected" },
                ifscCode = root.optString("ifscCode", "").trim().ifBlank { "Not Detected" },
                statementPeriod = root.optString("statementPeriod", "").trim().ifBlank { "Detected Statement Period" }
            )
            val txnsArr = root.optJSONArray("transactions")
            val parsedRows = mutableListOf<RawParsedTransaction>()
            val isoRegex = Regex("""^\d{4}-\d{2}-\d{2}$""")
            if (txnsArr != null) {
                for (i in 0 until txnsArr.length()) {
                    val obj = txnsArr.optJSONObject(i) ?: continue
                    val dateIso = obj.optString("dateIso", "").trim()
                    val desc = obj.optString("description", "").trim()
                    val amount = obj.optDouble("signedAmount", Double.NaN)
                    val balance = obj.optDouble("balance", Double.NaN)
                    if (isoRegex.matches(dateIso) && desc.isNotBlank() && !amount.isNaN() && !balance.isNaN()) {
                        parsedRows.add(
                            RawParsedTransaction(
                                dateIso = dateIso,
                                displayDate = formatIsoDisplay(dateIso),
                                chequeNo = obj.optString("chequeNo", "").trim(),
                                description = desc,
                                withdrawal = if (amount < 0) -amount else null,
                                deposit = if (amount > 0) amount else null,
                                signedAmount = amount,
                                normalizedBalance = balance,
                                balancePolarityExplicit = if (balance < 0) "DR" else "CR",
                                sourceFile = fileName
                            )
                        )
                    }
                }
            }
            SmartLayoutRecoveryResult(
                metadata = meta,
                transactions = parsedRows,
                statusNote = if (parsedRows.isNotEmpty()) {
                    "Cloud Layout Recovery extracted ${parsedRows.size} row(s) from non-standard PDF layout."
                } else {
                    "Cloud Layout Recovery found no additional transaction rows."
                }
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Cloud layout recovery timed out", e)
            SmartLayoutRecoveryResult(null, emptyList(), "Cloud Layout Recovery timed out (55s).")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Cloud layout recovery failed", e)
            SmartLayoutRecoveryResult(
                null,
                emptyList(),
                "Cloud Layout Recovery failed: ${e.localizedMessage ?: "unexpected error"}"
            )
        }
    }

    private fun formatIsoDisplay(iso: String): String {
        val parts = iso.split("-")
        if (parts.size != 3) return iso
        val months = arrayOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val y = parts[0].toIntOrNull() ?: return iso
        val m = parts[1].toIntOrNull()?.coerceIn(1, 12) ?: return iso
        val d = parts[2].toIntOrNull()?.coerceIn(1, 31) ?: return iso
        return String.format(java.util.Locale.ROOT, "%02d-%s-%02d", d, months[m], y % 100)
    }
}
