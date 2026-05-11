package dev.alsatianconsulting.NFCommunicator.domain

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException

enum class StorageBackend(val label: String) {
    Ndef("NDEF"),
    MifareClassicRaw("MIFARE Classic raw"),
    Unknown("Unknown"),
}

data class TagInfo(
    val capacityBytes: Int?,
    val totalCapacityBytes: Int? = capacityBytes,
    val isWritable: Boolean?,
    val technologies: List<String>,
    val storageBackend: StorageBackend = StorageBackend.Unknown,
    val tagDescription: String? = null,
)

data class NfcOperationResult(
    val isSuccess: Boolean,
    val statusMessage: String,
    val diagnosticDetail: String? = null,
    val decryptedMessage: String? = null,
    val tagInfo: TagInfo,
)

data class NfcPayloadReadResult(
    val isSuccess: Boolean,
    val statusMessage: String,
    val diagnosticDetail: String? = null,
    val encryptedPayload: ByteArray? = null,
    val tagInfo: TagInfo,
) {
    // ByteArray uses reference equality in Kotlin's generated equals; override to use structural
    // equality so that two results carrying equivalent payloads compare as equal (CQ-2).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NfcPayloadReadResult) return false
        return isSuccess == other.isSuccess &&
            statusMessage == other.statusMessage &&
            diagnosticDetail == other.diagnosticDetail &&
            (encryptedPayload === other.encryptedPayload ||
                (encryptedPayload != null && other.encryptedPayload != null &&
                    encryptedPayload.contentEquals(other.encryptedPayload))) &&
            tagInfo == other.tagInfo
    }

    override fun hashCode(): Int {
        var result = isSuccess.hashCode()
        result = 31 * result + statusMessage.hashCode()
        result = 31 * result + (diagnosticDetail?.hashCode() ?: 0)
        result = 31 * result + (encryptedPayload?.contentHashCode() ?: 0)
        result = 31 * result + tagInfo.hashCode()
        return result
    }
}

class NfcTagService {
    fun inspectTag(tag: Tag): TagInfo {
        val mifareClassic = MifareClassic.get(tag)
        if (mifareClassic != null) {
            return buildMifareTagInfo(tag, mifareClassic = mifareClassic)
        }

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return buildTagInfo(tag, ndef = ndef, storageBackend = StorageBackend.Ndef)
        }

        return buildTagInfo(tag)
    }

    fun readEncryptedMessage(tag: Tag, password: String): NfcOperationResult {
        val payloadResult = readEncryptedPayload(tag)
        if (!payloadResult.isSuccess || payloadResult.encryptedPayload == null) {
            return payloadResult.toOperationResult()
        }

        return try {
            val message = SecureMessageCodec.decryptPayload(payloadResult.encryptedPayload, password)
            successResult(
                tagInfo = payloadResult.tagInfo,
                statusMessage =
                    when (payloadResult.tagInfo.storageBackend) {
                        StorageBackend.MifareClassicRaw -> "Message decrypted successfully from raw MIFARE Classic storage."
                        StorageBackend.Ndef, StorageBackend.Unknown -> "Message decrypted successfully from NDEF."
                    },
                decryptedMessage = message,
            )
        } catch (error: InvalidPasswordException) {
            errorResult(payloadResult.tagInfo, "Wrong password or corrupted encrypted message.")
        } catch (error: InvalidPayloadException) {
            errorResult(payloadResult.tagInfo, "This app's encrypted message is unreadable.")
        }
    }

    fun readEncryptedPayload(tag: Tag): NfcPayloadReadResult {
        if (MifareClassic.get(tag) != null) {
            Log.i(logTag, "read payload backend=MIFARE_CLASSIC_RAW techs=${describeTag(tag)}")
            return readPayloadFromMifareClassic(tag)
        }

        val ndef = Ndef.get(tag)
            ?: return payloadErrorResult(tag, NfcDiagnostics.unsupportedRead(describeTechnologies(tag)))

        Log.i(logTag, "read payload backend=NDEF techs=${describeTag(tag)}")
        return readPayloadFromNdef(tag, ndef)
    }

    fun writeEncryptedMessage(tag: Tag, password: String, message: String): NfcOperationResult {
        val encryptedPayload = SecureMessageCodec.encryptToPayload(message, password)
        if (MifareClassic.get(tag) != null) {
            Log.i(logTag, "write backend=MIFARE_CLASSIC_RAW techs=${describeTag(tag)}")
            return writeToMifareClassic(tag, encryptedPayload)
        }

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            val ndefMessage = SecureMessageCodec.createNdefMessage(encryptedPayload)
            Log.i(logTag, "write backend=NDEF techs=${describeTag(tag)}")
            return writeToFormattedTag(tag, ndef, ndefMessage, ndefMessage.toByteArray().size)
        }

        val formatable = NdefFormatable.get(tag)
            ?: return errorResult(tag, NfcDiagnostics.unsupportedWrite(describeTechnologies(tag)))

        return try {
            val ndefMessage = SecureMessageCodec.createNdefMessage(encryptedPayload)
            formatable.connect()
            formatable.format(ndefMessage)
            successResult(
                tagInfo = buildTagInfo(
                    tag = tag,
                    capacityBytes = null,
                    isWritable = true,
                    storageBackend = StorageBackend.Ndef,
                ),
                statusMessage = "Encrypted message written to the newly formatted tag as NDEF.",
            )
        } catch (error: IOException) {
            errorResult(tag, "Unable to format or write this tag.")
        } catch (error: FormatException) {
            errorResult(tag, "Android rejected the encrypted payload format for this tag.")
        } finally {
            closeQuietly(formatable)
        }
    }

    fun clearTag(tag: Tag): NfcOperationResult {
        val emptyMessage = SecureMessageCodec.createEmptyNdefMessage()
        if (MifareClassic.get(tag) != null) {
            Log.i(logTag, "clear backend=MIFARE_CLASSIC_RAW techs=${describeTag(tag)}")
            return clearMifareClassic(tag)
        }

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            Log.i(logTag, "clear backend=NDEF techs=${describeTag(tag)}")
            val info = buildTagInfo(tag, ndef = ndef, storageBackend = StorageBackend.Ndef)
            return try {
                ndef.connect()
                val connectedInfo = buildTagInfo(tag, ndef = ndef, storageBackend = StorageBackend.Ndef)
                if (!ndef.isWritable) {
                    errorResult(connectedInfo, "This tag is read-only and cannot be cleared.")
                } else {
                    ndef.writeNdefMessage(emptyMessage)
                    successResult(connectedInfo, "The tag's NDEF content was cleared.")
                }
            } catch (error: IOException) {
                errorResult(info, "Unable to clear the tag. Hold it steady and try again.")
            } catch (error: FormatException) {
                errorResult(info, "Android rejected the empty NDEF message for this tag.")
            } finally {
                closeQuietly(ndef)
            }
        }

        val formatable = NdefFormatable.get(tag)
            ?: return errorResult(tag, NfcDiagnostics.unsupportedClear(describeTechnologies(tag)))

        return try {
            formatable.connect()
            formatable.format(emptyMessage)
            successResult(
                tagInfo = buildTagInfo(
                    tag = tag,
                    capacityBytes = null,
                    isWritable = true,
                    storageBackend = StorageBackend.Ndef,
                ),
                statusMessage = "The tag was formatted with an empty NDEF message.",
            )
        } catch (error: IOException) {
            errorResult(tag, "Unable to clear this tag.")
        } catch (error: FormatException) {
            errorResult(tag, "Android rejected the empty NDEF message for this tag.")
        } finally {
            closeQuietly(formatable)
        }
    }

    private fun readPayloadFromNdef(
        tag: Tag,
        ndef: Ndef,
    ): NfcPayloadReadResult {
        val baseInfo = buildTagInfo(tag, ndef = ndef, storageBackend = StorageBackend.Ndef)

        return try {
            ndef.connect()
            val info = buildTagInfo(tag, ndef = ndef, storageBackend = StorageBackend.Ndef)
            val ndefMessage = ndef.ndefMessage
                ?: return payloadErrorResult(info, NfcDiagnostics.ndefNoMessageFound(isEmpty = true))

            val record = ndefMessage.records.firstOrNull(SecureMessageCodec::isCompatibleRecord)
                ?: return payloadErrorResult(info, NfcDiagnostics.ndefNoMessageFound(isEmpty = false))

            payloadSuccessResult(
                tagInfo = info,
                statusMessage = "Encrypted message found on the NDEF tag.",
                encryptedPayload = record.payload,
            )
        } catch (error: InvalidPayloadException) {
            payloadErrorResult(baseInfo, NfcDiagnostics.ndefPayloadFailure())
        } catch (error: IOException) {
            payloadErrorResult(baseInfo, NfcDiagnostics.ndefIoFailure(action = "read", error = error))
        } finally {
            closeQuietly(ndef)
        }
    }

    private fun readPayloadFromMifareClassic(tag: Tag): NfcPayloadReadResult {
        val baseInfo = buildMifareTagInfo(tag)
        var info = baseInfo

        return try {
            val storage = runMifareClassicOperation(tag) { mifareClassic ->
                val headerPlan = discoverAccessibleMifareBlocks(
                    mifareClassic = mifareClassic,
                    requiredBytes = MifareClassic.BLOCK_SIZE,
                )
                if (headerPlan.blocks.isEmpty()) {
                    return@runMifareClassicOperation MifarePayloadRead(rawBytes = null, plan = headerPlan)
                }

                val headerBytes = readMifareClassicBlocks(
                    mifareClassic = mifareClassic,
                    blockPlan = headerPlan,
                    requiredBytes = headerPlan.usableBytes,
                )
                val requiredBytes = SecureMessageCodec.requiredMifareClassicStorageSize(headerBytes)
                val payloadPlan = discoverAccessibleMifareBlocks(
                    mifareClassic = mifareClassic,
                    requiredBytes = requiredBytes,
                )
                if (payloadPlan.usableBytes < requiredBytes) {
                    throw MifarePayloadTruncatedException("The MIFARE Classic payload is truncated.")
                }

                val rawBytes =
                    if (payloadPlan.blocks.size == headerPlan.blocks.size) {
                        headerBytes.copyOf(requiredBytes)
                    } else {
                        readMifareClassicBlocks(
                            mifareClassic = mifareClassic,
                            blockPlan = payloadPlan,
                            requiredBytes = requiredBytes,
                        )
                    }

                MifarePayloadRead(rawBytes = rawBytes, plan = payloadPlan)
            }

            info = buildMifareTagInfo(
                tag = tag,
                capacityBytes = storage.plan.capacityBytes,
                isWritable = null,
            )
            Log.i(
                logTag,
                "read mifare blocks=${storage.plan.blocks.size} usableBytes=${storage.plan.usableBytes} exactCapacity=${storage.plan.capacityExact}",
            )
            if (storage.rawBytes == null) {
                return payloadErrorResult(info, NfcDiagnostics.mifareAuthenticationFailure())
            }

            val encryptedPayload = SecureMessageCodec.extractFromMifareClassic(storage.rawBytes)
            payloadSuccessResult(
                tagInfo = info,
                statusMessage = "Encrypted message found in raw MIFARE Classic storage.",
                encryptedPayload = encryptedPayload,
            )
        } catch (error: InvalidPayloadException) {
            payloadErrorResult(info, NfcDiagnostics.mifarePayloadFailure(error))
        } catch (error: IOException) {
            payloadErrorResult(info, NfcDiagnostics.mifareIoFailure(action = "read", error = error))
        }
    }

    private fun writeToFormattedTag(
        tag: Tag,
        ndef: Ndef,
        message: NdefMessage,
        requiredBytes: Int,
    ): NfcOperationResult {
        val info = buildTagInfo(tag, ndef = ndef, storageBackend = StorageBackend.Ndef)
        return try {
            ndef.connect()
            val connectedInfo = buildTagInfo(tag, ndef = ndef, storageBackend = StorageBackend.Ndef)
            if (!ndef.isWritable) {
                return errorResult(connectedInfo, "This tag is read-only and cannot be overwritten.")
            }
            if (ndef.maxSize < requiredBytes) {
                return errorResult(
                    connectedInfo,
                    "Encrypted message needs $requiredBytes bytes, but this tag only exposes ${ndef.maxSize} bytes.",
                )
            }

            ndef.writeNdefMessage(message)
            successResult(
                tagInfo = connectedInfo,
                statusMessage = "Encrypted NDEF message written successfully. $requiredBytes of ${ndef.maxSize} bytes used.",
            )
        } catch (error: IOException) {
            errorResult(info, NfcDiagnostics.ndefIoFailure(action = "write", error = error))
        } catch (error: FormatException) {
            errorResult(info, "Android rejected the encrypted payload format for this tag.")
        } finally {
            closeQuietly(ndef)
        }
    }

    private fun writeToMifareClassic(tag: Tag, encryptedPayload: ByteArray): NfcOperationResult {
        val rawPayload = SecureMessageCodec.wrapForMifareClassic(encryptedPayload)
        val baseInfo = buildMifareTagInfo(tag)
        var info = baseInfo

        return try {
            val blockPlan = runMifareClassicOperation(tag) { mifareClassic ->
                val discoveredBlocks = discoverAccessibleMifareBlocks(
                    mifareClassic = mifareClassic,
                    requiredBytes = rawPayload.size,
                )
                if (discoveredBlocks.blocks.isEmpty() || discoveredBlocks.usableBytes < rawPayload.size) {
                    return@runMifareClassicOperation discoveredBlocks
                }

                writeMifareClassicBlocks(
                    mifareClassic = mifareClassic,
                    blockPlan = discoveredBlocks,
                    bytes = rawPayload.copyOf(discoveredBlocks.usableBytes),
                )
                discoveredBlocks
            }
            Log.i(
                logTag,
                "write mifare blocks=${blockPlan.blocks.size} usableBytes=${blockPlan.usableBytes} exactCapacity=${blockPlan.capacityExact} requiredBytes=${rawPayload.size}",
            )
            info = buildMifareTagInfo(
                tag = tag,
                capacityBytes = blockPlan.capacityBytes,
                isWritable = blockPlan.blocks.isNotEmpty(),
            )
            if (blockPlan.blocks.isEmpty()) {
                return errorResult(info, NfcDiagnostics.mifareAuthenticationFailure())
            }
            if (rawPayload.size > blockPlan.usableBytes) {
                return errorResult(
                    info,
                    "Encrypted message needs ${rawPayload.size} bytes in raw MIFARE Classic storage, but only ${blockPlan.usableBytes} bytes are accessible.",
                )
            }

            successResult(
                tagInfo = info,
                statusMessage =
                    if (info.capacityBytes != null) {
                        "Encrypted message written to raw MIFARE Classic storage. ${rawPayload.size} of ${info.capacityBytes} bytes used."
                    } else {
                        "Encrypted message written to raw MIFARE Classic storage. ${rawPayload.size} bytes used."
                    },
            )
        } catch (error: IOException) {
            errorResult(info, NfcDiagnostics.mifareIoFailure(action = "write", error = error))
        }
    }

    private fun clearMifareClassic(tag: Tag): NfcOperationResult {
        val baseInfo = buildMifareTagInfo(tag)
        var info = baseInfo

        return try {
            val blockPlan = runMifareClassicOperation(tag) { mifareClassic ->
                val discoveredBlocks = discoverAccessibleMifareBlocks(mifareClassic)
                if (discoveredBlocks.blocks.isEmpty()) {
                    return@runMifareClassicOperation discoveredBlocks
                }

                writeMifareClassicBlocks(
                    mifareClassic = mifareClassic,
                    blockPlan = discoveredBlocks,
                    bytes = ByteArray(discoveredBlocks.usableBytes),
                )
                discoveredBlocks
            }
            Log.i(
                logTag,
                "clear mifare blocks=${blockPlan.blocks.size} usableBytes=${blockPlan.usableBytes} exactCapacity=${blockPlan.capacityExact}",
            )
            info = buildMifareTagInfo(
                tag = tag,
                capacityBytes = blockPlan.capacityBytes,
                isWritable = blockPlan.blocks.isNotEmpty(),
            )
            if (blockPlan.blocks.isEmpty()) {
                return errorResult(info, NfcDiagnostics.mifareAuthenticationFailure())
            }

            successResult(info, "The raw MIFARE Classic storage used by this app was cleared.")
        } catch (error: IOException) {
            errorResult(info, NfcDiagnostics.mifareIoFailure(action = "clear", error = error))
        }
    }

    private fun discoverAccessibleMifareBlocks(
        mifareClassic: MifareClassic,
        requiredBytes: Int? = null,
    ): MifareBlockPlan {
        val requiredBlockCount =
            requiredBytes?.let { bytes ->
                ((bytes + MifareClassic.BLOCK_SIZE - 1) / MifareClassic.BLOCK_SIZE).coerceAtLeast(1)
            }
        val blocks = mutableListOf<MifareDataBlock>()
        // Sector 0 Block 0 is the manufacturer read-only block. Blocks 1–2 of sector 0 are
        // technically writable, but skipping the entire sector avoids any risk of overwriting
        // manufacturer/MAD data on cards that reserve sector 0 for directory purposes.
        for (sectorIndex in 1 until mifareClassic.sectorCount) {
            if (!authenticateMifareSector(mifareClassic, sectorIndex)) {
                continue
            }

            val firstBlock = mifareClassic.sectorToBlock(sectorIndex)
            val dataBlockCount = mifareClassic.getBlockCountInSector(sectorIndex) - 1
            for (offset in 0 until dataBlockCount) {
                blocks += MifareDataBlock(sectorIndex = sectorIndex, blockIndex = firstBlock + offset)
                if (requiredBlockCount != null && blocks.size >= requiredBlockCount) {
                    return MifareBlockPlan(blocks = blocks, capacityExact = false)
                }
            }
        }
        return MifareBlockPlan(blocks = blocks, capacityExact = true)
    }

    private fun readMifareClassicBlocks(
        mifareClassic: MifareClassic,
        blockPlan: MifareBlockPlan,
        requiredBytes: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream(blockPlan.usableBytes)
        var authenticatedSector = -1
        blockPlan.blocks.forEach { block ->
            if (authenticatedSector != block.sectorIndex) {
                if (!authenticateMifareSector(mifareClassic, block.sectorIndex)) {
                    throw IOException("Lost authentication for sector ${block.sectorIndex}.")
                }
                authenticatedSector = block.sectorIndex
            }

            output.write(mifareClassic.readBlock(block.blockIndex))
        }
        return output.toByteArray().copyOf(requiredBytes)
    }

    private fun writeMifareClassicBlocks(
        mifareClassic: MifareClassic,
        blockPlan: MifareBlockPlan,
        bytes: ByteArray,
    ) {
        var offset = 0
        var authenticatedSector = -1
        blockPlan.blocks.forEach { block ->
            if (authenticatedSector != block.sectorIndex) {
                if (!authenticateMifareSector(mifareClassic, block.sectorIndex)) {
                    throw IOException("Lost authentication for sector ${block.sectorIndex}.")
                }
                authenticatedSector = block.sectorIndex
            }

            val blockBytes = bytes.copyOfRange(offset, offset + MifareClassic.BLOCK_SIZE)
            mifareClassic.writeBlock(block.blockIndex, blockBytes)
            offset += MifareClassic.BLOCK_SIZE
        }
    }

    private inline fun <T> runMifareClassicOperation(tag: Tag, block: (MifareClassic) -> T): T {
        var lastError: IOException? = null
        repeat(mifareIoAttempts) { attempt ->
            val mifareClassic = MifareClassic.get(tag)
                ?: throw IOException("This tag does not expose MIFARE Classic on this device.")
            try {
                mifareClassic.connect()
                return block(mifareClassic)
            } catch (error: IOException) {
                lastError = error
                Log.w(logTag, "MIFARE Classic attempt ${attempt + 1} failed.", error)
            } finally {
                closeQuietly(mifareClassic)
            }
        }
        throw lastError ?: IOException("Unable to access the MIFARE Classic tag.")
    }

    private fun authenticateMifareSector(
        mifareClassic: MifareClassic,
        sectorIndex: Int,
    ): Boolean {
        commonMifareKeys.forEach { key ->
            if (mifareClassic.authenticateSectorWithKeyA(sectorIndex, key)) {
                return true
            }
            if (mifareClassic.authenticateSectorWithKeyB(sectorIndex, key)) {
                return true
            }
        }
        return false
    }

    private fun buildTagInfo(
        tag: Tag,
        ndef: Ndef? = null,
        capacityBytes: Int? = ndef?.maxSize,
        totalCapacityBytes: Int? = ndef?.maxSize,
        isWritable: Boolean? = ndef?.isWritable,
        storageBackend: StorageBackend = StorageBackend.Unknown,
        tagDescription: String? = null,
    ): TagInfo =
        TagInfo(
            capacityBytes = capacityBytes,
            totalCapacityBytes = totalCapacityBytes,
            isWritable = isWritable,
            technologies = describeTechnologies(tag),
            storageBackend = storageBackend,
            tagDescription = tagDescription,
        )

    private fun buildMifareTagInfo(
        tag: Tag,
        mifareClassic: MifareClassic? = MifareClassic.get(tag),
        capacityBytes: Int? = null,
        isWritable: Boolean? = null,
    ): TagInfo {
        val usableCapacityBytes = mifareClassic?.let(::estimateUsableMifareClassicCapacity)
        return buildTagInfo(
            tag = tag,
            capacityBytes = capacityBytes ?: usableCapacityBytes,
            totalCapacityBytes = mifareClassic?.size,
            isWritable = isWritable,
            storageBackend = StorageBackend.MifareClassicRaw,
            tagDescription = mifareClassic?.let(::describeMifareClassic),
        )
    }

    private fun describeTechnologies(tag: Tag): List<String> =
        tag.techList.map { it.substringAfterLast('.') }

    private fun describeTag(tag: Tag): String =
        describeTechnologies(tag).joinToString(",")

    private fun estimateUsableMifareClassicCapacity(mifareClassic: MifareClassic): Int {
        var dataBlocks = 0
        for (sectorIndex in 1 until mifareClassic.sectorCount) {
            dataBlocks += mifareClassic.getBlockCountInSector(sectorIndex) - 1
        }
        return dataBlocks * MifareClassic.BLOCK_SIZE
    }

    private fun describeMifareClassic(mifareClassic: MifareClassic): String {
        val family = when (mifareClassic.type) {
            MifareClassic.TYPE_CLASSIC -> "MIFARE Classic"
            MifareClassic.TYPE_PLUS -> "MIFARE Plus"
            MifareClassic.TYPE_PRO -> "MIFARE Pro"
            else -> "MIFARE-compatible"
        }
        val sizeLabel = when (mifareClassic.size) {
            320 -> "Mini"
            1_024 -> "1K"
            2_048 -> "2K"
            4_096 -> "4K"
            else -> "${mifareClassic.size} bytes"
        }
        return "$family $sizeLabel • ${mifareClassic.sectorCount} sectors • ${mifareClassic.blockCount} blocks"
    }

    private fun successResult(
        tagInfo: TagInfo,
        statusMessage: String,
        decryptedMessage: String? = null,
    ): NfcOperationResult =
        NfcOperationResult(
            isSuccess = true,
            statusMessage = statusMessage,
            decryptedMessage = decryptedMessage,
            tagInfo = tagInfo,
        )

    private fun payloadSuccessResult(
        tagInfo: TagInfo,
        statusMessage: String,
        encryptedPayload: ByteArray,
    ): NfcPayloadReadResult =
        NfcPayloadReadResult(
            isSuccess = true,
            statusMessage = statusMessage,
            encryptedPayload = encryptedPayload,
            tagInfo = tagInfo,
        )

    private fun errorResult(tag: Tag, statusMessage: String): NfcOperationResult =
        errorResult(buildTagInfo(tag), statusMessage)

    private fun errorResult(tag: Tag, diagnosticMessage: DiagnosticMessage): NfcOperationResult =
        errorResult(buildTagInfo(tag), diagnosticMessage)

    private fun errorResult(tagInfo: TagInfo, statusMessage: String): NfcOperationResult =
        NfcOperationResult(
            isSuccess = false,
            statusMessage = statusMessage,
            tagInfo = tagInfo,
        )

    private fun errorResult(tagInfo: TagInfo, diagnosticMessage: DiagnosticMessage): NfcOperationResult =
        NfcOperationResult(
            isSuccess = false,
            statusMessage = diagnosticMessage.statusMessage,
            diagnosticDetail = diagnosticMessage.detail,
            tagInfo = tagInfo,
        )

    private fun payloadErrorResult(tag: Tag, diagnosticMessage: DiagnosticMessage): NfcPayloadReadResult =
        payloadErrorResult(buildTagInfo(tag), diagnosticMessage)

    private fun payloadErrorResult(tagInfo: TagInfo, diagnosticMessage: DiagnosticMessage): NfcPayloadReadResult =
        NfcPayloadReadResult(
            isSuccess = false,
            statusMessage = diagnosticMessage.statusMessage,
            diagnosticDetail = diagnosticMessage.detail,
            tagInfo = tagInfo,
        )

    private fun NfcPayloadReadResult.toOperationResult(): NfcOperationResult =
        NfcOperationResult(
            isSuccess = isSuccess,
            statusMessage = statusMessage,
            diagnosticDetail = diagnosticDetail,
            tagInfo = tagInfo,
        )

    private fun closeQuietly(ndef: Ndef) {
        try {
            ndef.close()
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    private fun closeQuietly(formatable: NdefFormatable) {
        try {
            formatable.close()
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    private fun closeQuietly(mifareClassic: MifareClassic) {
        try {
            mifareClassic.close()
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    private data class MifareDataBlock(
        val sectorIndex: Int,
        val blockIndex: Int,
    )

    private data class MifareBlockPlan(
        val blocks: List<MifareDataBlock>,
        val capacityExact: Boolean,
    ) {
        val usableBytes: Int =
            blocks.size * MifareClassic.BLOCK_SIZE

        val capacityBytes: Int? =
            usableBytes.takeIf { capacityExact && blocks.isNotEmpty() }
    }

    private data class MifarePayloadRead(
        val rawBytes: ByteArray?,
        val plan: MifareBlockPlan,
    )

    private companion object {
        const val logTag = "NfcTagService"
        const val mifareIoAttempts = 2
        val commonMifareKeys: List<ByteArray> = listOf(
            MifareClassic.KEY_DEFAULT,
            MifareClassic.KEY_NFC_FORUM,
            MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY,
        )
    }
}
