package io.nayuki.deflate;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;


/**
 * Decompresses a DEFLATE data stream (raw format without zlib or gzip headers or footers) into a byte stream.
 * <p>Incomplete functionality - currently only supports uncompressed blocks.</p>
 */
public final class InflaterInputStream extends FilterInputStream {
	
	/*---- Fields ----*/
	
	// Buffer of bytes read from in.read() (the underlying input stream)
	private byte[] inputBuffer;     // Can have any positive length (but longer means less overhead)
	private int inputBufferLength;  // Number of valid prefix bytes, or -1 to indicate end of stream
	private int inputBufferIndex;   // Index of next byte to consume
	
	// Buffer of bits read from the bytes in 'inputBuffer'
	private long inputBitBuffer;       // 0 <= value < 2^inputBitBufferLength
	private int inputBitBufferLength;  // Always in the range [0, 63]
	
	// Buffer of last 32 KiB of decoded data
	private static final int DICTIONARY_LENGTH = 32 * 1024;
	private static final int DICTIONARY_MASK = DICTIONARY_LENGTH - 1;
	private byte[] dictionary;
	private int dictionaryIndex;
	
	// -3: A data format exception has been thrown.
	// -2: This inflater stream has been closed.
	// -1: Currently processing a Huffman-compressed block.
	// 0 to 65535: Number of bytes remaining in current uncompressed block.
	private int state;
	
	// Indicates whether a block header with the "bfinal" flag has been seen.
	private boolean isLastBlock;
	
	private short[] literalLengthCodeTree;  // Must be null when and only when state != -1
	private short[] distanceCodeTree;       // Must be null when state != -1
	
	
	
	/*---- Public API methods ----*/
	
	public InflaterInputStream(InputStream in) {
		super(in);
		
		// Initialize data buffers
		inputBuffer = new byte[16 * 1024];
		inputBufferLength = 0;
		inputBufferIndex = 0;
		inputBitBuffer = 0;
		inputBitBufferLength = 0;
		dictionary = new byte[DICTIONARY_LENGTH];
		dictionaryIndex = 0;
		
		// Initialize state
		state = 0;
		isLastBlock = false;
		literalLengthCodeTree = null;
		distanceCodeTree = null;
	}
	
	
	
	public int read() throws IOException {
		byte[] b = new byte[1];
		while (true) {
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
	
	
	public int read(byte[] b, int off, int len) throws IOException {
		// Check state and arguments
		if (state == -2)
			throw new IllegalStateException("Stream already closed");
		if (state == -3)
			throw new IOException("The stream contained invalid data");
		if (off < 0 || off > b.length || len < 0 || b.length - off < len)
			throw new IndexOutOfBoundsException();
		
		// Get into a block
		while (state == 0) {
			if (isLastBlock)
				return -1;
			if (len == 0)
				return 0;
			
			isLastBlock = readBits(1) == 1;
			int type = readBits(2);
			if (type == 0) {
				alignInputToByte();
				state = readBits(16);  // Block length
				if (state != (readBits(16) ^ 0xFFFF))
					invalidData("len/nlen mismatch in uncompressed block");
			} else if (type == 1) {
				state = -1;
				literalLengthCodeTree = FIXED_LITERAL_LENGTH_CODE_TREE;
				distanceCodeTree = FIXED_DISTANCE_CODE_TREE;
			} else if (type == 2) {
				throw new UnsupportedOperationException("Dynamic Huffman blocks not supported");
			} else if (type == 3)
				invalidData("Reserved block type");
			else
				throw new AssertionError();
		}
		
		if (1 <= state && state <= 0xFFFF) {
			// Read from uncompressed block
			int n = Math.min(state, len);
			readBytes(b, off, n);
			for (int i = 0; i < n; i++) {
				dictionary[dictionaryIndex] = b[off + i];
				dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
			}
			state -= n;
			return n;
			
		} else if (state == -1) {
			int n = 0;
			while (n < len) {
				int sym = decodeSymbol(literalLengthCodeTree);
				assert 0 <= sym && sym <= 287;
				if (sym < 256) {  // Literal byte
					b[off] = (byte)sym;
					off++;
					dictionary[dictionaryIndex] = (byte)sym;
					dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
					n++;
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
						if (n == len)
							throw new UnsupportedOperationException("Cannot handle LZ77 run beyond output buffer");
						b[off] = bb;
						off++;
						dictionary[dictionaryIndex] = bb;
						dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
						dictReadIndex = (dictReadIndex + 1) & DICTIONARY_MASK;
						n++;
					}
					
				} else {  // sym == 256, end of block
					literalLengthCodeTree = null;
					distanceCodeTree = null;
					break;
				}
			}
			return n;
			
		} else
			throw new AssertionError();
	}
	
	
	public void close() throws IOException {
		state = -2;
		isLastBlock = true;
		super.close();
		
		// Clear buffers
		inputBuffer = null;
		inputBufferLength = 0;
		inputBufferIndex = 0;
		inputBitBuffer = 0;
		inputBitBufferLength = 0;
	}
	
	
	/*---- Huffman coding methods ----*/
	
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
	private static short[] codeLengthsToCodeTree(byte[] codeLengths) throws DataFormatException {
		final short UNUSED  = 0x7000;
		final short OPENING = 0x7001;
		final short OPEN    = 0x7002;
		
		short[] result = new short[(codeLengths.length - 1) * 2];  // Worst-case allocation if all symbols are present
		Arrays.fill(result, UNUSED);
		result[0] = OPEN;
		result[1] = OPEN;
		int allocated = 2;  // Always even in this algorithm
		
		int maxCodeLen = 0;
		for (int x : codeLengths)
			maxCodeLen = Math.max(x, maxCodeLen);
		assert maxCodeLen <= 15;
		
		// Allocate Huffman tree nodes according to ascending code lengths
		for (int curCodeLen = 1; curCodeLen <= maxCodeLen; curCodeLen++) {
			// Loop invariant: Each OPEN child slot in the result array has depth curCodeLen
			
			// Allocate all symbols of current code length to open slots in ascending order
			int resultIndex = 0;
			int symbol = 0;
			middle:
			while (true) {
				// Find next symbol having current code length
				while (symbol < codeLengths.length && codeLengths[symbol] != curCodeLen) {
					assert codeLengths[symbol] >= 0;
					symbol++;
				}
				if (symbol == codeLengths.length)
					break middle;  // No more symbols to process
				
				// Find next open child slot
				while (resultIndex < result.length && result[resultIndex] != OPEN)
					resultIndex++;
				if (resultIndex == result.length)  // No more slots left; tree over-full
					throw new DataFormatException("This canonical code does not represent a Huffman code tree");
				
				// Put the symbol in the slot and increment
				result[resultIndex] = (short)~symbol;
				resultIndex++;
				symbol++;
			}
			
			// Take all open slots and deepen them by one level
			for (; resultIndex < result.length; resultIndex++) {
				if (result[resultIndex] == OPEN) {
					// Allocate a new node
					assert allocated + 2 <= result.length;
					result[resultIndex] = (short)allocated;
					result[allocated + 0] = OPENING;
					result[allocated + 1] = OPENING;
					allocated += 2;
				}
			}
			
			// Do post-processing so we don't open slots that were just opened
			for (resultIndex = 0; resultIndex < result.length; resultIndex++) {
				if (result[resultIndex] == OPENING)
					result[resultIndex] = OPEN;
			}
		}
		
		// Check for under-full tree after all symbols are allocated
		for (int i = 0; i < allocated; i++) {
			if (result[i] == OPEN)
				throw new DataFormatException("This canonical code does not represent a Huffman code tree");
		}
		
		return result;
	}
	
	
	private int decodeSymbol(short[] codeTree) throws IOException {
		int node = 0;
		
		int count = inputBitBufferLength;
		if (count > 0) {  // Medium path using buffered bits
			// Because of this truncation, the code tree depth needs to be no more than 32
			int bits = (int)inputBitBuffer;
			do {
				node = codeTree[node + (bits & 1)];
				bits >>>= 1;
				count--;
			} while (count > 0 && node >= 0);
			inputBitBuffer >>>= inputBitBufferLength - count;
		inputBitBufferLength = count;
		}
		
		while (node >= 0)  // Slow path reading one bit at a time
			node = codeTree[node + readBits(1)];
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
			throw new AssertionError();
		}
	}
	
	
	private int decodeDistance(int sym) throws IOException {
		assert 0 <= sym && sym < 32;
		if (sym <= 3)
			return sym + 1;
		else if (sym <= 29) {
			int numExtraBits = (sym >>> 1) - 1;
			return (((sym & 1) | 2) << numExtraBits) + 1 + readBits(numExtraBits);
		} else {  // sym is 30 or 31
			invalidData("Reserved distance symbol: " + sym);
			throw new AssertionError();
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
				throw new AssertionError();
			
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
		assert 0 <= inputBitBufferLength && inputBitBufferLength <= 63;
		assert inputBitBuffer >>> inputBitBufferLength == 0;
		
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
			System.arraycopy(inputBuffer, inputBufferIndex, b, off, n);
			inputBufferIndex += n;
			off += n;
			len -= n;
		}
		
		// Read directly from input stream
		while (len > 0) {
			int n = in.read(b, off, len);
			if (n == -1) {
				inputBufferIndex = 0;
				inputBufferLength = -1;
				state = -3;
				isLastBlock = true;
				throw new EOFException();
			}
			off += n;
			len -= n;
		}
	}
	
	
	// Fills the input byte buffer with new data read from the underlying input stream.
	// Requires the buffer to be fully consumed before being called.
	// Sets inputBufferLength to a number in the range [-1, inputBuffer.length].
	private void fillInputBuffer() throws IOException {
		if (inputBufferIndex < inputBufferLength)
			throw new AssertionError("Input buffer not fully consumed yet");
		inputBufferLength = in.read(inputBuffer);
		inputBufferIndex = 0;
		if (inputBufferLength == -1) {
			state = -3;
			isLastBlock = true;
			throw new EOFException();
		}
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
	
	
	private void invalidData(String reason) throws IOException {
		state = -3;
		isLastBlock = true;
		throw new IOException("Invalid DEFLATE data: " + reason);
	}
	
	
	/*---- Static tables ----*/
	
	private static final short[] FIXED_LITERAL_LENGTH_CODE_TREE;
	private static final short[] FIXED_DISTANCE_CODE_TREE;
	
	static {
		try {
			byte[] llcodelens = new byte[288];
			Arrays.fill(llcodelens,   0, 144, (byte)8);
			Arrays.fill(llcodelens, 144, 256, (byte)9);
			Arrays.fill(llcodelens, 256, 280, (byte)7);
			Arrays.fill(llcodelens, 280, 288, (byte)8);
			FIXED_LITERAL_LENGTH_CODE_TREE = codeLengthsToCodeTree(llcodelens);
			
			byte[] distcodelens = new byte[32];
			Arrays.fill(distcodelens, (byte)5);
			FIXED_DISTANCE_CODE_TREE = codeLengthsToCodeTree(distcodelens);
		} catch (DataFormatException e) {
			throw new AssertionError(e);
		}
	}
	
}
