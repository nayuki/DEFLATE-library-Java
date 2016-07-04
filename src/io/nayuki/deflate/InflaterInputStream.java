package io.nayuki.deflate;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;


/**
 * Decompresses a DEFLATE data stream (raw format without zlib or gzip headers or footers) into a byte stream.
 */
public final class InflaterInputStream extends FilterInputStream {
	
	/*---- Fields ----*/
	
	/* Data buffers */
	
	// Buffer of bytes read from in.read() (the underlying input stream)
	private byte[] inputBuffer;     // Can have any positive length (but longer means less overhead)
	private int inputBufferLength;  // Number of valid prefix bytes, or -1 to indicate end of stream
	private int inputBufferIndex;   // Index of next byte to consume
	
	// Buffer of bits packed from the bytes in 'inputBuffer'
	private long inputBitBuffer;       // 0 <= value < 2^inputBitBufferLength
	private int inputBitBufferLength;  // Always in the range [0, 63]
	
	// Queued bytes to yield first when this.read() is called
	private byte[] outputBuffer;     // Should have length 257 (but pointless if longer)
	private int outputBufferLength;  // Number of valid prefix bytes, at least 0
	private int outputBufferIndex;   // Index of next byte to produce, in the range [0, outputBufferLength]
	
	// Buffer of last 32 KiB of decoded data, for LZ77 decompression
	private byte[] dictionary;
	private int dictionaryIndex;
	
	
	/* Configuration */
	
	// Indicates whether mark() should be called when the underlying
	// input stream is read, and whether calling detach() is allowed.
	private final boolean isDetachable;
	
	
	/* State */
	
	// The state of the decompressor:
	//   -3: This decompressor stream has been closed.
	//   -2: A data format exception has been thrown.
	//   -1: Currently processing a Huffman-compressed block.
	//    0: Initial state, or a block just ended.
	//   1 to 65535: Currently processing an uncompressed block, number of bytes remaining.
	private int state;
	
	// Indicates whether a block header with the "bfinal" flag has been seen.
	private boolean isLastBlock;
	
	// Current code trees for when state == -1. When state != -1, both must be null.
	private short[] literalLengthCodeTree;  // When state == -1, this must be not null
	private short[] distanceCodeTree;  // When state == -1, this can be null or not null
	
	
	
	/*---- Constructors ----*/
	
	/**
	 * Constructs an inflater input stream over the specified underlying input stream, and with the
	 * specified option for detachability. The underlying stream must contain DEFLATE-compressed data with
	 * no headers or footers (e.g. must be unwrapped from the zlib or gzip container formats). Detachability
	 * allows {@link #detach()} to be called, and requires the specified input stream to support marking.
	 * @param in the underlying input stream of raw DEFLATE-compressed data
	 * @param detachable whether {@code detach()} can be called later
	 * @throws NullPointerException if the input stream is {@code null}
	 * @throws IllegalArgumentException if {@code detach == true} but {@code in.markSupported() == false}
	 */
	public InflaterInputStream(InputStream in, boolean detachable) {
		this(in, detachable, 16 * 1024);
	}
	
	
	/**
	 * Constructs an inflater input stream over the specified underlying input stream, with the
	 * specified options for detachability and input buffer size. The underlying stream must
	 * contain DEFLATE-compressed data with no headers or footers (e.g. must be unwrapped from
	 * the zlib or gzip container formats). Detachability allows {@link #detach()} to be called,
	 * and requires the specified input stream to support marking.
	 * @param in the underlying input stream of raw DEFLATE-compressed data
	 * @param detachable whether {@code detach()} can be called later
	 * @param inBufLen the size of the internal read buffer, which must be positive
	 * @throws NullPointerException if the input stream is {@code null}
	 * @throws IllegalArgumentException if {@code inBufLen < 1}
	 * @throws IllegalArgumentException if {@code detach == true} but {@code in.markSupported() == false}
	 */
	public InflaterInputStream(InputStream in, boolean detachable, int inBufLen) {
		// Handle the input stream and detachability
		super(in);
		if (inBufLen <= 0)
			throw new IllegalArgumentException("Input buffer size must be positive");
		isDetachable = detachable;
		if (detachable) {
			if (in.markSupported())
				in.mark(0);
			else
				throw new IllegalArgumentException("Input stream not markable, cannot support detachment");
		}
		
		// Initialize data buffers
		inputBuffer = new byte[inBufLen];
		inputBufferLength = 0;
		inputBufferIndex = 0;
		inputBitBuffer = 0;
		inputBitBufferLength = 0;
		outputBuffer = new byte[257];
		outputBufferLength = 0;
		outputBufferIndex = 0;
		dictionary = new byte[DICTIONARY_LENGTH];
		dictionaryIndex = 0;
		
		// Initialize state
		state = 0;
		isLastBlock = false;
		literalLengthCodeTree = null;
		distanceCodeTree = null;
	}
	
	
	
	/*---- Public API methods ----*/
	
	/**
	 * Reads the next byte of decompressed data from this stream. If data is available
	 * then a number in the range [0, 255] is returned (blocking if necessary);
	 * otherwise &minus;1 is returned if the end of stream is reached.
	 * @return the next unsigned byte of data, or &minus;1 for the end of stream
	 * @throws IOException if an I/O exception occurred in the underlying input stream, the end of
	 * stream was encountered at an unexpected position, or the compressed data has a format error
	 * @throws IllegalStateException if the stream has already been closed
	 */
	public int read() throws IOException {
		while (true) {
			byte[] b = new byte[1];
			switch (read(b)) {
				case 1:
					return (b[0] & 0xFF);
				case 0:
					continue;
				case -1:
					return -1;
				default:
					throw new AssertionError();
			}
		}
	}
	
	
	/**
	 * Reads some bytes from the decompressed data of this stream into the specified array's subrange.
	 * This returns the number of data bytes that were stored into the array, and is in the range
	 * [&minus;1, len]. Note that 0 can be returned even if the end of stream hasn't been reached yet.
	 * @throws NullPointerException if the array is {@code null}
	 * @throws ArrayIndexOutOfBoundsException if the array subrange is out of bounds
	 * @throws IOException if an I/O exception occurred in the underlying input stream, the end of
	 * stream was encountered at an unexpected position, or the compressed data has a format error
	 * @throws IllegalStateException if the stream has already been closed
	 */
	public int read(byte[] b, int off, int len) throws IOException {
		// Check arguments and state
		if (b == null)
			throw new NullPointerException();
		if (off < 0 || off > b.length || len < 0 || b.length - off < len)
			throw new ArrayIndexOutOfBoundsException();
		if (in == null)
			throw new IllegalStateException("Stream already closed");
		if (state == -2)
			throw new IOException("The stream contained invalid data");
		
		// Special handling for empty read request
		if (len == 0)
			return (outputBufferLength > 0 || state != 0 || !isLastBlock) ? 0 : -1;
		assert len > 0;
		
		int result = 0;  // Number of bytes filled in the array 'b'
		
		// First move bytes (if any) from the output buffer
		if (outputBufferLength > 0) {
			int n = Math.min(outputBufferLength - outputBufferIndex, len);
			System.arraycopy(outputBuffer, outputBufferIndex, b, off, n);
			result = n;
			outputBufferIndex += n;
			if (outputBufferIndex == outputBufferLength) {
				outputBufferLength = 0;
				outputBufferIndex = 0;
			}
			if (result == len)
				return result;
		}
		assert outputBufferLength == 0 && outputBufferIndex == 0 && result < len;
		
		// Get into a block if not already inside one
		while (state == 0) {
			if (isLastBlock)
				return -1;
			
			// Read and process block header
			isLastBlock = readBits(1) == 1;
			switch (readBits(2)) {  // Type
				case 0:
					alignInputToByte();
					state = readBits(16);  // Block length
					if (state != (readBits(16) ^ 0xFFFF))
						invalidData("len/nlen mismatch in uncompressed block");
					break;
				case 1:
					state = -1;
					literalLengthCodeTree = FIXED_LITERAL_LENGTH_CODE_TREE;
					distanceCodeTree = FIXED_DISTANCE_CODE_TREE;
					break;
				case 2:
					state = -1;
					decodeHuffmanCodes();
					break;
				case 3:
					invalidData("Reserved block type");
					break;
				default:
					throw new AssertionError();
			}
		}
		
		// Read the block's data into the argument array
		if (1 <= state && state <= 0xFFFF) {
			// Read bytes from uncompressed block
			int toRead = Math.min(state, len - result);
			readBytes(b, off + result, toRead);
			for (int i = 0; i < toRead; i++) {
				dictionary[dictionaryIndex] = b[off + result];
				dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
				result++;
			}
			state -= toRead;
			return result;
			
		} else if (state == -1) {
			// Decode symbols from Huffman-coded block
			while (result < len) {
				int sym = decodeSymbol(literalLengthCodeTree);
				assert 0 <= sym && sym <= 287;
				if (sym < 256) {  // Literal byte
					b[off + result] = (byte)sym;
					dictionary[dictionaryIndex] = (byte)sym;
					dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
					result++;
				} else if (sym > 256) {  // Length and distance for copying
					int run = decodeRunLength(sym);
					assert 3 <= run && run <= 258;
					if (distanceCodeTree == null)
						invalidData("Length symbol encountered with empty distance code");
					int distSym = decodeSymbol(distanceCodeTree);
					assert 0 <= distSym && distSym <= 31;
					int dist = decodeDistance(distSym);
					assert 1 <= dist && dist <= 32768;
					
					// Copy bytes to output and dictionary
					int dictReadIndex = (dictionaryIndex - dist) & DICTIONARY_MASK;
					for (int i = 0; i < run; i++) {
						byte bb = dictionary[dictReadIndex];
						dictReadIndex = (dictReadIndex + 1) & DICTIONARY_MASK;
						dictionary[dictionaryIndex] = bb;
						dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
						if (result < len) {
							b[off + result] = bb;
							result++;
						} else {
							assert outputBufferLength < outputBuffer.length;
							outputBuffer[outputBufferLength] = bb;
							outputBufferLength++;
						}
					}
				} else {  // sym == 256, end of block
					literalLengthCodeTree = null;
					distanceCodeTree = null;
					state = 0;
					break;
				}
			}
			return result;
			
		} else
			throw new AssertionError();
	}
	
	
	/**
	 * Detaches the underlying input stream from this decompressor. This puts the underlying stream
	 * at the position of the first byte after the data that this decompressor actually consumed.
	 * <p>This method exists because for efficiency, the decompressor may read more bytes from the
	 * underlying stream than necessary to produce the decompressed data. If you want to continue
	 * reading the underlying stream exactly after the point the DEFLATE-compressed data ends,
	 * then it is necessary to call this detach method.</p>
	 * <p>This can only be called once, and is mutually exclusive with respect to calling
	 * {@link #close()}. It is illegal to call {@link #read()} after detaching.</p>
	 * @throws IllegalStateException if detach was already called or this stream has been closed
	 * @throws IOException if an I/O exception occurred
	 */
	public void detach() throws IOException {
		if (!isDetachable)
			throw new IllegalStateException("Detachability not specified at construction");
		if (in == null)
			throw new IllegalStateException("Input stream already detached/closed");
		
		// Adjust over-consumed bytes
		in.reset();
		int skip = inputBufferIndex - inputBitBufferLength / 8;  // Note: A partial byte is considered to be consumed
		assert skip >= 0;
		while (skip > 0) {
			long n = in.skip(skip);
			if (n <= 0)
				throw new EOFException();
			skip -= n;
		}
		
		in = null;
		state = -3;
		isLastBlock = true;
		literalLengthCodeTree = null;
		distanceCodeTree = null;
		releaseBuffers();
	}
	
	
	/**
	 * Closes this input stream and the underlying stream.
	 * It is illegal to call {@link #read()} or {@link #detach()} after closing.
	 * It is idempotent to call this {@link #close()} method more than once.
	 * @throws IOException if an I/O exception occurred in the underlying stream
	 */
	public void close() throws IOException {
		if (in == null)
			return;
		super.close();
		in = null;
		state = -3;
		isLastBlock = true;
		literalLengthCodeTree = null;
		distanceCodeTree = null;
		releaseBuffers();
	}
	
	
	/*---- Huffman coding methods ----*/
	
	private void decodeHuffmanCodes() throws IOException {
		int numLitLenCodes  = readBits(5) + 257;  // hlit  + 257
		int numDistCodes    = readBits(5) +   1;  // hdist +   1
		
		int numCodeLenCodes = readBits(4) +   4;  // hclen +   4
		byte[] codeLenCodeLen = new byte[19];
		for (int i = 0; i < numCodeLenCodes; i++)
			codeLenCodeLen[CODE_LENGTH_CODE_ORDER[i]] = (byte)readBits(3);
		short[] codeLenCodeTree = codeLengthsToCodeTree(codeLenCodeLen);
		
		byte[] codeLens = new byte[numLitLenCodes + numDistCodes];
		byte runVal = -1;
		int runLen = 0;
		for (int i = 0; i < codeLens.length; ) {
			if (runLen > 0) {
				assert runVal != -1;
				codeLens[i] = runVal;
				runLen--;
				i++;
			} else {
				int sym = decodeSymbol(codeLenCodeTree);
				assert 0 <= sym && sym <= 18;
				if (sym < 16) {
					runVal = codeLens[i] = (byte)sym;
					i++;
				} else if (sym == 16) {
					if (runVal == -1)
						invalidData("No code length value to copy");
					runLen = readBits(2) + 3;
				} else if (sym == 17) {
					runVal = 0;
					runLen = readBits(3) + 3;
				} else {  // sym == 18
					runVal = 0;
					runLen = readBits(7) + 11;
				}
			}
		}
		if (runLen > 0)
			invalidData("Run exceeds number of codes");
		
		// Create code trees
		byte[] litLenCodeLen = Arrays.copyOf(codeLens, numLitLenCodes);
		literalLengthCodeTree = codeLengthsToCodeTree(litLenCodeLen);
		
		byte[] distCodeLen = Arrays.copyOfRange(codeLens, numLitLenCodes, codeLens.length);
		if (distCodeLen.length == 1 && distCodeLen[0] == 0)
			distanceCodeTree = null;  // Empty distance code; the block shall be all literal symbols
		else {
			// Get statistics for upcoming logic
			int oneCount = 0;
			int otherPositiveCount = 0;
			for (byte x : distCodeLen) {
				if (x == 1)
					oneCount++;
				else if (x > 1)
					otherPositiveCount++;
			}
			
			// Handle the case where only one distance code is defined
			if (oneCount == 1 && otherPositiveCount == 0) {
				// Add a dummy invalid code to make the Huffman tree complete
				distCodeLen = Arrays.copyOf(distCodeLen, 32);
				distCodeLen[31] = 1;
			}
			distanceCodeTree = codeLengthsToCodeTree(distCodeLen);
		}
	}
	
	
	/* 
	 * Converts the given array of symbol code lengths into a canonical code tree.
	 * A symbol code length is either zero (absent from the tree) or a positive integer.
	 * 
	 * A code tree is an array of integers, where each pair represents a node.
	 * Each pair is adjacent and starts on an even index. The first element of
	 * the pair represents the left child and the second element represents the
	 * right child. The root node is at index 0. If an element is non-negative,
	 * then it is the index of the child node in the array. Otherwise it is the
	 * bitwise complement of the leaf symbol. This tree is used in decodeSymbol()
	 * and codeTreeToCodeTable(). Not every element of the array needs to be
	 * used, nor do used elements need to be contiguous.
	 * 
	 * For example, this Huffman tree:
	 *        o
	 *       / \
	 *      o   \
	 *     / \   \
	 *   'a' 'b' 'c'
	 * is serialized as this array:
	 *   {2, ~'c', ~'a', ~'b'}
	 * because the root is located at index 0 and the other internal node is
	 * located at index 2.
	 */
	private short[] codeLengthsToCodeTree(byte[] codeLengths) throws IOException {
		short[] result = new short[(codeLengths.length - 1) * 2];  // Worst-case allocation if all symbols are present
		Arrays.fill(result, CODE_TREE_UNUSED_SLOT);
		result[0] = CODE_TREE_OPEN_SLOT;
		result[1] = CODE_TREE_OPEN_SLOT;
		int allocated = 2;  // Always even in this algorithm
		
		int maxCodeLen = 0;
		for (byte cl : codeLengths) {
			assert 0 <= cl && cl <= 15;
			maxCodeLen = Math.max(cl, maxCodeLen);
		}
		if (maxCodeLen > 15)
			throw new AssertionError("Maximum code length exceeds DEFLATE specification");
		
		// Allocate Huffman tree nodes according to ascending code lengths
		for (int curCodeLen = 1; curCodeLen <= maxCodeLen; curCodeLen++) {
			// Loop invariant: Each OPEN child slot in the result array has depth curCodeLen
			
			// Allocate all symbols of current code length to open slots in ascending order
			int resultIndex = 0;
			for (int symbol = 0; ; ) {
				// Find next symbol having current code length
				while (symbol < codeLengths.length && codeLengths[symbol] != curCodeLen)
					symbol++;
				if (symbol == codeLengths.length)
					break;  // No more symbols to process
				
				// Find next open child slot
				while (resultIndex < allocated && result[resultIndex] != CODE_TREE_OPEN_SLOT)
					resultIndex++;
				if (resultIndex == allocated)  // No more slots left
					invalidData("Canonical code fails to produce full Huffman code tree");
				
				// Put the symbol in the slot and increment
				result[resultIndex] = (short)~symbol;
				resultIndex++;
				symbol++;
			}
			
			// Take all open slots and deepen them by one level
			for (int end = allocated; resultIndex < end; resultIndex++) {
				if (result[resultIndex] == CODE_TREE_OPEN_SLOT) {
					// Allocate a new node
					assert allocated + 2 <= result.length;
					result[resultIndex] = (short)allocated;
					result[allocated + 0] = CODE_TREE_OPEN_SLOT;
					result[allocated + 1] = CODE_TREE_OPEN_SLOT;
					allocated += 2;
				}
			}
		}
		
		// Check for under-full tree after all symbols are allocated
		for (int i = 0; i < allocated; i++) {
			if (result[i] == CODE_TREE_OPEN_SLOT)
				invalidData("Canonical code fails to produce full Huffman code tree");
		}
		
		return result;
	}
	
	
	private int decodeSymbol(short[] codeTree) throws IOException {
		int node = 0;
		while (node >= 0) {
			if (inputBitBufferLength > 0) {  // Medium path using buffered bits
				node = codeTree[node + ((int)inputBitBuffer & 1)];
				inputBitBuffer >>>= 1;
				inputBitBufferLength--;
			} else  // Slow path with potential I/O operations
				node = codeTree[node + readBits(1)];
		}
		return ~node;  // Symbol encoded in bitwise complement
	}
	
	
	private int decodeRunLength(int sym) throws IOException {
		assert 257 <= sym && sym <= 287;
		if (sym <= 264)
			return sym - 254;
		else if (sym <= 284) {
			int numExtraBits = (sym - 261) >>> 2;
			return ((((sym - 1) & 3) | 4) << numExtraBits) + 3 + readBits(numExtraBits);
		} else if (sym == 285)
			return 258;
		else {  // sym is 286 or 287
			invalidData("Reserved run length symbol: " + sym);
			throw new AssertionError("Unreachable");
		}
	}
	
	
	private int decodeDistance(int sym) throws IOException {
		assert 0 <= sym && sym <= 31;
		if (sym <= 3)
			return sym + 1;
		else if (sym <= 29) {
			int numExtraBits = (sym >>> 1) - 1;
			return (((sym & 1) | 2) << numExtraBits) + 1 + readBits(numExtraBits);
		} else {  // sym is 30 or 31
			invalidData("Reserved distance symbol: " + sym);
			throw new AssertionError("Unreachable");
		}
	}
	
	
	/*---- I/O methods ----*/
	
	// Returns the given number of least significant bits from the bit buffer,
	// which updates the bit buffer and possibly also the byte buffer.
	private int readBits(int numBits) throws IOException {
		// Check arguments and invariants
		assert 1 <= numBits && numBits <= 16;  // Max value used in DEFLATE is 16, but this method is designed to be valid for numBits <= 31
		assert 0 <= inputBitBufferLength && inputBitBufferLength <= 63;
		assert inputBitBuffer >>> inputBitBufferLength == 0;  // Ensure high-order bits are clean
		
		// Ensure there is enough data in the bit buffer to satisfy the request
		byte[] b = inputBuffer;  // Shorter name
		while (inputBitBufferLength < numBits) {
			int i = inputBufferIndex;  // Shorter name
			
			// Pack as many bytes as possible from input byte buffer into the bit buffer
			int numBytes = Math.min((64 - inputBitBufferLength) >>> 3, inputBufferLength - i);
			long temp;  // Bytes packed in little endian
			if (numBytes == 8) {  // ~90% hit rate
				temp =     (((b[i]&0xFF) | (b[i+1]&0xFF)<<8 | (b[i+2]&0xFF)<<16 | b[i+3]<<24) & 0xFFFFFFFFL) |
				    (long)((b[i+4]&0xFF) | (b[i+5]&0xFF)<<8 | (b[i+6]&0xFF)<<16 | b[i+7]<<24) << 32;
			} else if (numBytes == 7) {  // ~5% hit rate
				temp =     (((b[i]&0xFF) | (b[i+1]&0xFF)<<8 | (b[i+2]&0xFF)<<16 | b[i+3]<<24) & 0xFFFFFFFFL) |
				    (long)((b[i+4]&0xFF) | (b[i+5]&0xFF)<<8 | (b[i+6]&0xFF)<<16) << 32;
			} else if (numBytes == 6) {
				temp =     (((b[i]&0xFF) | (b[i+1]&0xFF)<<8 | (b[i+2]&0xFF)<<16 | b[i+3]<<24) & 0xFFFFFFFFL) |
				    (long)((b[i+4]&0xFF) | (b[i+5]&0xFF)<<8) << 32;
			} else if (numBytes > 0) {
				// This slower general logic is valid for 1 <= bytes <= 8
				temp = 0;
				for (int j = 0; j < numBytes; i++, j++)
					temp |= (b[i] & 0xFFL) << (j << 3);
			} else if (numBytes == 0 && inputBufferLength != -1) {
				// Fill and retry
				fillInputBuffer();
				continue;
			} else
				throw new AssertionError("Unreachable state");
			
			// Update the buffer
			inputBitBuffer |= temp << inputBitBufferLength;
			inputBitBufferLength += numBytes << 3;
			inputBufferIndex += numBytes;
			assert inputBitBufferLength <= 64;
		}
		
		// Extract bits to return
		int result = (int)inputBitBuffer & ((1 << numBits) - 1);
		inputBitBuffer >>>= numBits;
		inputBitBufferLength -= numBits;
		
		// Check return and recheck invariants
		assert result >>> numBits == 0;
		assert 0 <= inputBitBufferLength && inputBitBufferLength <= 63;
		assert inputBitBuffer >>> inputBitBufferLength == 0;
		return result;
	}
	
	
	private void readBytes(byte[] b, int off, int len) throws IOException {
		// Check bit buffer invariants
		if (inputBitBufferLength < 0 || inputBitBufferLength > 63
				|| inputBitBuffer >>> inputBitBufferLength != 0)
			throw new AssertionError("Invalid input bit buffer state");
		
		// Unpack saved bits first
		alignInputToByte();
		for (; len > 0 && inputBitBufferLength >= 8; off++, len--) {
			b[off] = (byte)inputBitBuffer;
			inputBitBuffer >>>= 8;
			inputBitBufferLength -= 8;
		}
		
		// Read from input buffer
		{
			int n = Math.min(len, inputBufferLength - inputBufferIndex);
			assert inputBitBufferLength == 0 || n == 0;
			System.arraycopy(inputBuffer, inputBufferIndex, b, off, n);
			inputBufferIndex += n;
			off += n;
			len -= n;
		}
		
		// Read directly from input stream
		while (len > 0) {
			assert inputBufferIndex == inputBufferLength;
			int n = in.read(b, off, len);
			if (n == -1)
				invalidData("Unexpected end of stream");
			off += n;
			len -= n;
		}
	}
	
	
	// Fills the input byte buffer with new data read from the underlying input stream.
	// Requires the buffer to be fully consumed before being called.
	// Sets inputBufferLength to a number in the range [-1, inputBuffer.length].
	private void fillInputBuffer() throws IOException {
		if (state < -1)
			throw new AssertionError("Must not read in this state");
		if (inputBufferIndex < inputBufferLength)
			throw new AssertionError("Input buffer not fully consumed yet");
		
		if (isDetachable)
			in.mark(inputBuffer.length);
		inputBufferLength = in.read(inputBuffer);
		inputBufferIndex = 0;
		if (inputBufferLength == -1)
			invalidData("Unexpected end of stream");  // Note: This sets inputBufferLength to 0
		if (inputBufferLength < -1 || inputBufferLength > inputBuffer.length)
			throw new AssertionError();
	}
	
	
	// Discards the remaining bits (0 to 7) in the current byte being read, if any.
	private void alignInputToByte() {
		int discard = inputBitBufferLength & 7;
		inputBitBuffer >>>= discard;
		inputBitBufferLength -= discard;
		assert inputBitBufferLength % 8 == 0;
	}
	
	
	/*---- State management methods ----*/
	
	// Throws an IOException with the given reason, and destroys the state of this decompressor.
	private void invalidData(String reason) throws IOException {
		state = -2;
		isLastBlock = true;
		literalLengthCodeTree = null;
		distanceCodeTree = null;
		releaseBuffers();
		// Do not set 'in' to null, so that calling close() is still possible
		throw new IOException("Invalid DEFLATE data: " + reason);
	}
	
	
	// Sets all buffer arrays to null and related variables to 0.
	// It is illegal to call read() or detach() after this method is called.
	// The caller is responsible for manipulating other state variables appropriately.
	private void releaseBuffers() {
		inputBuffer = null;
		inputBufferLength = 0;
		inputBufferIndex = 0;
		inputBitBuffer = 0;
		inputBitBufferLength = 0;
		outputBuffer = null;
		outputBufferLength = 0;
		outputBufferIndex = 0;
		dictionary = null;
		dictionaryIndex = 0;
	}
	
	
	/*---- Constants and tables ----*/
	
	private static final int[] CODE_LENGTH_CODE_ORDER =
		{16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
	
	private static final short[] FIXED_LITERAL_LENGTH_CODE_TREE;
	private static final short[] FIXED_DISTANCE_CODE_TREE;
	
	static {
		try {
			byte[] llcodelens = new byte[288];
			Arrays.fill(llcodelens,   0, 144, (byte)8);
			Arrays.fill(llcodelens, 144, 256, (byte)9);
			Arrays.fill(llcodelens, 256, 280, (byte)7);
			Arrays.fill(llcodelens, 280, 288, (byte)8);
			
			byte[] distcodelens = new byte[32];
			Arrays.fill(distcodelens, (byte)5);
			
			InflaterInputStream dummy = new InflaterInputStream(
				new ByteArrayInputStream(new byte[0]), false);
			FIXED_LITERAL_LENGTH_CODE_TREE = dummy.codeLengthsToCodeTree(llcodelens);
			FIXED_DISTANCE_CODE_TREE = dummy.codeLengthsToCodeTree(distcodelens);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
	
	
	// Must be a power of 2. Do not change this constant value. If the value is decreased, then
	// decompression may produce different data that violates the DEFLATE spec (but no crashes).
	// If the value is increased, the behavior stays the same but memory is wasted with no benefit.
	private static final int DICTIONARY_LENGTH = 32 * 1024;
	
	// This is why the above must be a power of 2.
	private static final int DICTIONARY_MASK = DICTIONARY_LENGTH - 1;
	
	
	private static final short CODE_TREE_UNUSED_SLOT = 0x7000;
	private static final short CODE_TREE_OPEN_SLOT   = 0x7002;
	
}
