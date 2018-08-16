package org.kethereum.bip39

import org.kethereum.bip32.generateKey
import org.kethereum.extensions.toBitArray
import org.kethereum.extensions.toByteArray
import org.kethereum.hashes.sha256
import java.security.SecureRandom
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// TODO inline class once Kotlin 1.3 is out
data class MnemonicWords(val words: Array<String>) {
    constructor(phrase: String) : this(phrase.split(" "))
    constructor(phrase: List<String>) : this(phrase.toTypedArray())

    override fun toString() = words.joinToString(" ")
    override fun equals(other: Any?) = toString() == other?.toString()
    override fun hashCode() = Arrays.hashCode(words)
}

fun dirtyPhraseToMnemonicWords(string: String) =  MnemonicWords(string.trim().toLowerCase()
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotEmpty() })

/**
 * Generates a seed buffer from a mnemonic phrase according to the BIP39 spec.
 * The mnemonic phrase is given as a list of words and the seed can be salted using a password
 */
fun MnemonicWords.toSeed(password: String = ""): ByteArray {
    val pass = words.joinToString(" ")
    val salt = "mnemonic$password"

    val keyFactory = SecretKeyFactory.getInstance("PBKDF2withHmacSHA512")
    val spec = PBEKeySpec(pass.toCharArray(), salt.toByteArray(), 2048, 512)
    return keyFactory.generateSecret(spec).encoded
}

/**
 * Converts a phrase (list of words) into a [ByteArray] entropy buffer according to the BIP39 spec
 */
fun mnemonicToEntropy(phrase: String, wordList: List<String>) =
        MnemonicWords(phrase).mnemonicToEntropy(wordList)


/**
 * Converts a list of words into a [ByteArray] entropy buffer according to the BIP39 spec
 */
fun MnemonicWords.mnemonicToEntropy(wordList: List<String>): ByteArray {
    if (words.size % 3 > 0)
        throw IllegalArgumentException("Word list size must be multiple of three words.")

    if (words.isEmpty())
        throw IllegalArgumentException("Word list is empty.")

    val numTotalBits = words.size * 11
    val bitArray = BooleanArray(numTotalBits)

    for ((phraseIndex, word) in words.withIndex()) {

        val dictIndex = Collections.binarySearch(wordList, word)
        if (dictIndex < 0)
            throw IllegalArgumentException("word($word) not in known word list")

        // Set the next 11 bits to the value of the index.
        for (bit in 0..10)
            bitArray[phraseIndex * 11 + bit] = dictIndex and (1 shl (10 - bit)) != 0
    }

    val numChecksumBits = numTotalBits / 33
    val numEntropyBits = numTotalBits - numChecksumBits

    val entropy = bitArray.toByteArray(numEntropyBits / 8)

    // Take the digest of the entropy.
    val hash = entropy.sha256()
    val hashBits = hash.toBitArray()

    // Check all the checksum bits.
    for (i in 0 until numChecksumBits)
        if (bitArray[numEntropyBits + i] != hashBits[i])
            throw IllegalArgumentException("mnemonic checksum does not match")

    return entropy
}

fun MnemonicWords.toKey(path: String, saltPhrase: String = "") =
        generateKey(toSeed(saltPhrase), path)

/**
 * Converts an entropy buffer to a list of words according to the BIP39 spec
 */
fun entropyToMnemonic(entropy: ByteArray, wordList: List<String>): String {
    if (entropy.size % 4 > 0)
        throw RuntimeException("Entropy not multiple of 32 bits.")

    if (entropy.isEmpty())
        throw RuntimeException("Entropy is empty.")

    val hash = entropy.sha256()
    val hashBits = hash.toBitArray()

    val entropyBits = entropy.toBitArray()
    val checksumLengthBits = entropyBits.size / 32

    val concatBits = BooleanArray(entropyBits.size + checksumLengthBits)
    System.arraycopy(entropyBits, 0, concatBits, 0, entropyBits.size)
    System.arraycopy(hashBits, 0, concatBits, entropyBits.size, checksumLengthBits)


    val words = ArrayList<String>().toMutableList()
    val numWords = concatBits.size / 11
    for (i in 0 until numWords) {
        var index = 0
        for (j in 0..10) {
            index = index shl 1
            if (concatBits[i * 11 + j])
                index = index or 0x01
        }
        words.add(wordList[index])
    }

    return words.joinToString(" ")
}

/**
 * Generates a mnemonic phrase, given a desired [strength]
 * The [strength] represents the number of entropy bits this phrase encodes and needs to be a multiple of 32
 */
fun generateMnemonic(strength: Int = 128, wordList: List<String>): String {

    if (strength % 32 != 0) {
        throw IllegalArgumentException("The entropy strength needs to be a multiple of 32")
    }

    val entropyBuffer = ByteArray(strength / 8)
    SecureRandom().nextBytes(entropyBuffer)

    return entropyToMnemonic(entropyBuffer, wordList)
}

/**
 * Checks if MnemonicWords is a valid encoding according to the BIP39 spec
 */
fun MnemonicWords.validate(wordList: List<String>) = try {
    mnemonicToEntropy(wordList)
    true
} catch (e: Exception) {
    e.printStackTrace()
    false
}
