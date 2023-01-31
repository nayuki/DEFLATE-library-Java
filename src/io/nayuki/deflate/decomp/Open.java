/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.decomp;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import io.nayuki.deflate.DataFormatException;


public final class Open implements State {
	
	/*---- Fields ----*/
	
	// The underlying stream to read from
	public final InputStream input;
	
	// Indicates whether mark() should be called when the underlying
	// input stream is read, and whether calling detach() is allowed.
	private final boolean isDetachable;
	
	
	// The typical data flow in this decompressor looks like:
	//   input (the underlying input stream) -> input.read()
	//   -> inputBuffer -> packing logic in readBits()
	//   -> inputBitBuffer -> readBit() or equivalent
	//   -> Huffman decoding logic for literal and length-distance symbols
	//   -> LZ77 decoding logic -> dictionary
	//   -> copying to the caller's array
	//   -> b (the array passed into this.read(byte[],int,int)).
	
	// Buffer of bytes read from input.read() (the underlying input stream)
	private final ByteBuffer inputBuffer;  // Can have any positive length (but longer means less overhead)
	
	// Buffer of bits packed from the bytes in `inputBuffer`
	private long inputBitBuffer = 0;       // Always in the range [0, 2^inputBitBufferLength)
	private int inputBitBufferLength = 0;  // Always in the range [0, 63]
	
	
	private Optional<BlockDecoder> blockDecoder = Optional.empty();
	
	// Indicates whether a block header with the `bfinal` flag has been seen.
	// This starts as false, should eventually become true, and never changes back to false.
	private boolean isLastBlock = false;
	
	
	// Buffer of last 32 KiB of decoded data, for LZ77 decompression
	private final byte[] dictionary = new byte[DICTIONARY_LENGTH];
	private int dictionaryIndex = 0;  // Always in the range [0, dictionary.length)
	
	
	
	/*---- Constructor ----*/
	
	public Open(InputStream in, boolean detachable, int inBufLen) {
		input = in;
		isDetachable = detachable;
		inputBuffer = ByteBuffer.allocate(inBufLen).position(0).limit(0);
	}
	
	
	
	/*---- Public methods ----*/
	
	public int read(byte[] b, int off, int len) throws IOException {
		int result = 0;  // Number of bytes filled in the array `b`
		while (result < len) {
			if (blockDecoder.isEmpty()) {  // Between blocks
				if (isLastBlock)
					break;
				
				// Read and process the block header
				isLastBlock = readBits(1) == 1;
				blockDecoder = Optional.of(switch (readBits(2)) {  // Type
					case 0 -> new UncompressedBlock();
					case 1 -> new HuffmanBlock(false);
					case 2 -> new HuffmanBlock(true);
					case 3 -> throw new DataFormatException("Reserved block type");
					default -> throw new AssertionError("Unreachable value");
				});
			}
			
			BlockDecoder dec = blockDecoder.get();
			result += dec.read(b, off + result, len - result);
			if (dec.isDone())
				blockDecoder = Optional.empty();
		}
		return (result > 0 || blockDecoder.isPresent() || !isLastBlock) ? result : -1;
	}
	
	
	public void detach() throws IOException {
		if (!isDetachable)
			throw new IllegalStateException("Detachability not specified at construction");
		
		// Rewind the underlying stream, then skip over bytes that were already consumed.
		// Note that a byte with some bits consumed is considered to be fully consumed.
		input.reset();
		int skip = inputBuffer.position() - inputBitBufferLength / 8;
		assert skip >= 0;
		new DataInputStream(input).skipNBytes(skip);
	}
	
	
	public void close() throws IOException {
		input.close();
	}
	
	
	
	/*---- Private methods ----*/
	
	// Returns the given number of least significant bits from the bit buffer.
	// This updates the bit buffer state and possibly also the byte buffer state.
	private int readBits(int numBits) throws IOException {
		// Check arguments and invariants
		assert 1 <= numBits && numBits <= 16;  // Note: DEFLATE uses up to 16, but this method is correct up to 31
		assert 0 <= inputBitBufferLength && inputBitBufferLength <= 63;
		assert inputBitBuffer >>> inputBitBufferLength == 0;  // Ensure high-order bits are clean
		
		// Ensure there is enough data in the bit buffer to satisfy the request
		while (inputBitBufferLength < numBits) {
			if (!inputBuffer.hasRemaining())
				fillInputBuffer();
			
			// Pack as many bytes as possible from input byte buffer into the bit buffer
			int numBytes = Math.min((64 - inputBitBufferLength) >>> 3, inputBuffer.remaining());
			assert 0 <= numBytes && numBytes <= 8;
			for (int i = 0; i < numBytes; i++, inputBitBufferLength += 8)
				inputBitBuffer |= (inputBuffer.get() & 0xFFL) << inputBitBufferLength;
			assert 0 <= inputBitBufferLength && inputBitBufferLength <= 64;  // Can temporarily be 64
		}
		
		// Extract the bits to return
		int result = (int)inputBitBuffer & ((1 << numBits) - 1);
		assert result >>> numBits == 0;
		inputBitBuffer >>>= numBits;
		inputBitBufferLength -= numBits;
		
		// Recheck invariants
		assert 0 <= inputBitBufferLength && inputBitBufferLength <= 63;
		assert inputBitBuffer >>> inputBitBufferLength == 0;
		return result;
	}
	
	
	// Fills the empty input byte buffer with at least
	// one new byte read from the underlying input stream.
	private void fillInputBuffer() throws IOException {
		if (inputBuffer.hasRemaining())
			throw new AssertionError("Input buffer not fully consumed yet");
		if (isDetachable)
			input.mark(inputBuffer.capacity());
		int n = input.read(inputBuffer.array());
		if (n == -1)
			throw new EOFException("Unexpected end of stream");
		else if (n == 0)
			throw new AssertionError("read() returned zero bytes");
		else
			inputBuffer.position(0).limit(n);
	}
	
	
	// Discards the remaining bits (0 to 7) in the current byte being read, if any. Always succeeds.
	private void alignInputToByte() {
		int n = inputBitBufferLength & 7;
		inputBitBuffer >>>= n;
		inputBitBufferLength -= n;
		assert inputBitBufferLength % 8 == 0;
	}
	
	
	
	/*---- Constants ----*/
	
	// Must be a power of 2. Do not change this constant value. If the value is decreased, then
	// decompression may produce different data that violates the DEFLATE spec (but no crashes).
	// If the value is increased, the behavior stays the same but memory is wasted with no benefit.
	private static final int DICTIONARY_LENGTH = 32 * 1024;
	
	// This is why the above must be a power of 2.
	private static final int DICTIONARY_MASK = DICTIONARY_LENGTH - 1;
	
	static {
		if (DICTIONARY_LENGTH < 32 * 1024)
			throw new AssertionError("Dictionary length shorter than required by the specification");
		if (Integer.bitCount(DICTIONARY_LENGTH) != 1)
			throw new AssertionError("Dictionary length not a power of 2");  // Required for mask-based modulo calculation
	}
	
	
	
	/*---- Block decoder types ----*/
	
	private interface BlockDecoder {
		
		// Unlike InputStream.read(byte[]), this returns [0, len] but never -1.
		public int read(byte[] b, int off, int len) throws IOException;
		
		public boolean isDone();
		
	}
	
	
	private final class UncompressedBlock implements BlockDecoder {
		
		private int numRemainingBytes;  // Non-negative
		
		
		public UncompressedBlock() throws IOException {
			alignInputToByte();
			numRemainingBytes = readBits(16);
			assert 0x0000 <= numRemainingBytes && numRemainingBytes <= 0xFFFF;
			if (numRemainingBytes != (readBits(16) ^ 0xFFFF))
				throw new DataFormatException("len/nlen mismatch in uncompressed block");
		}
		
		
		public int read(byte[] b, final int off, int len) throws IOException {
			if (numRemainingBytes < 0)
				throw new AssertionError("Unreachable state");
			
			// Check bit buffer invariants
			if (inputBitBufferLength < 0 || inputBitBufferLength > 63
					|| inputBitBuffer >>> inputBitBufferLength != 0)
				throw new AssertionError("Invalid input bit buffer state");
			assert inputBitBufferLength % 8 == 0;
			
			len = Math.min(numRemainingBytes, len);
			numRemainingBytes -= len;
			int index = off;
			final int end = off + len;
			assert off <= end && end <= b.length;
			
			// First unpack saved bits
			for (; inputBitBufferLength >= 8 && index < end; index++)
				b[index] = (byte)readBits(8);
			
			// Copy from input buffer
			{
				int n = Math.min(end - index, inputBuffer.remaining());
				assert inputBitBufferLength == 0 || n == 0;
				inputBuffer.get(b, index, n);
				index += n;
			}
			
			// Read directly from input stream, bypassing the input buffer
			while (index < end) {
				assert inputBitBufferLength == 0 && !inputBuffer.hasRemaining();
				int n = input.read(b, index, end - index);
				if (n == -1)
					throw new EOFException("Unexpected end of stream");
				index += n;
			}
			
			// Copy output bytes to dictionary
			for (index = off; index < end; ) {
				int n = Math.min(end - index, dictionary.length - dictionaryIndex);
				System.arraycopy(b, index, dictionary, dictionaryIndex, n);
				index += n;
				dictionaryIndex = (dictionaryIndex + n) & DICTIONARY_MASK;
			}
			
			return len;
		}
		
		
		public boolean isDone() {
			if (numRemainingBytes < 0)
				throw new AssertionError("Unreachable state");
			return numRemainingBytes == 0;
		}
		
	}
	
	
	
	private final class HuffmanBlock implements BlockDecoder {
		
		private final short[] literalLengthCodeTree;   // Not null
		private final short[] literalLengthCodeTable;  // Derived from literalLengthCodeTree; not null
		private final short[] distanceCodeTree;   // Can be null
		private final short[] distanceCodeTable;  // Derived from distanceCodeTree; same nullness
		private final int maxBitsPerIteration;  // In the range [2, 48]
		
		private int numPendingOutputBytes = 0;  // Always in the range [0, MAX_RUN_LENGTH-1]
		private boolean isDone = false;
		
		
		public HuffmanBlock(boolean dynamic) throws IOException {
			if (!dynamic) {
				literalLengthCodeTree  = FIXED_LITERAL_LENGTH_CODE_TREE;
				literalLengthCodeTable = FIXED_LITERAL_LENGTH_CODE_TABLE;
				distanceCodeTree  = FIXED_DISTANCE_CODE_TREE;
				distanceCodeTable = FIXED_DISTANCE_CODE_TABLE;
				maxBitsPerIteration = 9 + 5 + 5 + 13;
			}
			else {
				// Read the current block's dynamic Huffman code tables from from the input
				// buffers/stream, process the code lengths and computes the code trees, and
				// ultimately set just the variables {literalLengthCodeTree, literalLengthCodeTable,
				// distanceCodeTree, distanceCodeTable}. This might throw an IOException for actual I/O
				// exceptions, unexpected end of stream, or a description of an invalid Huffman code.
				int numLitLenCodes  = readBits(5) + 257;  // hlit  + 257
				int numDistCodes    = readBits(5) +   1;  // hdist +   1
				
				// Read the code length code lengths
				int numCodeLenCodes = readBits(4) +   4;  // hclen +   4
				var codeLenCodeLen = new byte[CODE_LENGTH_CODE_ORDER.length];
				for (int i = 0; i < numCodeLenCodes; i++)  // Fill array in strange order
					codeLenCodeLen[CODE_LENGTH_CODE_ORDER[i]] = (byte)readBits(3);
				short[] codeLenCodeTree = codeLengthsToCodeTree(codeLenCodeLen);
				
				// Read the main code lengths and handle runs
				var codeLens = new byte[numLitLenCodes + numDistCodes];
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
						assert 0 <= sym && sym < codeLenCodeLen.length;
						if (sym < 16) {
							runVal = codeLens[i] = (byte)sym;
							i++;
						} else if (sym == 16) {
							if (runVal == -1)
								throw new DataFormatException("No code length value to copy");
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
					throw new DataFormatException("Run exceeds number of codes");
				
				// Create literal-length code tree
				byte[] litLenCodeLen = Arrays.copyOf(codeLens, numLitLenCodes);
				literalLengthCodeTree = codeLengthsToCodeTree(litLenCodeLen);
				literalLengthCodeTable = codeTreeToCodeTable(literalLengthCodeTree);
				int maxBitsPerLitLen = 0;
				for (int sym = 0; sym < litLenCodeLen.length; sym++) {
					int numBits = litLenCodeLen[sym];
					if (sym >= 257 && numBits > 0)
						numBits += RUN_LENGTH_TABLE[sym - 257] & 0x7;  // Extra bits
					maxBitsPerLitLen = Math.max(numBits, maxBitsPerLitLen);
				}
				
				// Create distance code tree with some extra processing
				byte[] distCodeLen = Arrays.copyOfRange(codeLens, numLitLenCodes, codeLens.length);
				int maxBitsPerDist = 0;
				if (distCodeLen.length == 1 && distCodeLen[0] == 0) {
					// Empty distance code; the block shall be all literal symbols
					distanceCodeTree = null;
					distanceCodeTable = null;
				} else {
					for (int sym = 0; sym < distCodeLen.length; sym++) {
						int numBits = distCodeLen[sym];
						if (numBits > 0 && sym < DISTANCE_TABLE.length)
							numBits += DISTANCE_TABLE[sym] & 0xF;  // Extra bits
						maxBitsPerDist = Math.max(numBits, maxBitsPerDist);
					}
					
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
					distanceCodeTable = codeTreeToCodeTable(distanceCodeTree);
				}
				
				maxBitsPerIteration = maxBitsPerLitLen + maxBitsPerDist;
			}
		}
		
		
		public int read(byte[] b, final int off, final int len) throws IOException {
			int index = off;
			final int end = off + len;
			assert off <= end && end <= b.length;
			
			for (; numPendingOutputBytes > 0 && index < end; numPendingOutputBytes--, index++)
				b[index] = dictionary[(dictionaryIndex - numPendingOutputBytes) & DICTIONARY_MASK];
			
			while (index < end) {
				assert numPendingOutputBytes == 0;
				assert 0 <= inputBitBufferLength && inputBitBufferLength <= 63;
				
				// Try to fill the input bit buffer (somewhat similar to logic in readBits())
				if (inputBitBufferLength < maxBitsPerIteration) {
					ByteBuffer c = inputBuffer;  // Shorter name
					int numBytes = Math.min((64 - inputBitBufferLength) >>> 3, inputBuffer.remaining());
					assert 0 <= numBytes && numBytes <= 8;
					switch (numBytes) {  // Only implement special cases that occur frequently in practice
						case 2:
							inputBitBuffer |= (long)((c.get()&0xFF) | (c.get()&0xFF)<<8) << inputBitBufferLength;
							inputBitBufferLength += 2 * 8;
							break;
						case 3:
							inputBitBuffer |= (long)((c.get()&0xFF) | (c.get()&0xFF)<<8 | (c.get()&0xFF)<<16) << inputBitBufferLength;
							inputBitBufferLength += 3 * 8;
							break;
						case 4:
							inputBitBuffer |= (((c.get()&0xFF) | (c.get()&0xFF)<<8 | (c.get()&0xFF)<<16 | c.get()<<24) & 0xFFFFFFFFL) << inputBitBufferLength;
							inputBitBufferLength += 4 * 8;
							break;
						case 5:
							inputBitBuffer |= ((c.get()&0xFFL) | (c.get()&0xFFL)<<8 | (c.get()&0xFFL)<<16 | (c.get()&0xFFL)<<24 | (c.get()&0xFFL)<<32) << inputBitBufferLength;
							inputBitBufferLength += 5 * 8;
							break;
						case 6:
							inputBitBuffer |= ((c.get()&0xFFL) | (c.get()&0xFFL)<<8 | (c.get()&0xFFL)<<16 | (c.get()&0xFFL)<<24 | (c.get()&0xFFL)<<32 | (c.get()&0xFFL)<<40) << inputBitBufferLength;
							inputBitBufferLength += 6 * 8;
							break;
						default:  // This slower general logic is valid for 0 <= numBytes <= 8
							for (int j = 0; j < numBytes; j++, inputBitBufferLength += 8)
								inputBitBuffer |= (c.get() & 0xFFL) << inputBitBufferLength;
							break;
					}
				}
				
				int run, dist;
				
				if (inputBitBufferLength >= maxBitsPerIteration) {  // Fast path entirely from bit buffer
					// Decode next literal/length symbol (a customized version of decodeSymbol())
					final int sym;
					{
						int temp = literalLengthCodeTable[(int)inputBitBuffer & CODE_TABLE_MASK];
						assert temp >= 0;  // No need to mask off sign extension bits
						int consumed = temp >>> 11;
						inputBitBuffer >>>= consumed;
						inputBitBufferLength -= consumed;
						int node = (temp << 21) >> 21;  // Sign extension from 11 bits
						while (node >= 0) {
							node = literalLengthCodeTree[node + ((int)inputBitBuffer & 1)];
							inputBitBuffer >>>= 1;
							inputBitBufferLength--;
						}
						sym = ~node;
					}
					
					// Handle the symbol by ranges
					assert 0 <= sym && sym <= 287;
					if (sym < 256) {  // Literal byte
						b[index] = (byte)sym;
						index++;
						dictionary[dictionaryIndex] = (byte)sym;
						dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
						continue;
						
					} else if (sym > 256) {  // Length and distance for copying
						// Decode the run length (a customized version of decodeRunLength())
						assert 257 <= sym && sym <= 287;
						if (sym > 285)
							throw new DataFormatException("Reserved run length symbol: " + sym);
						{
							int temp = RUN_LENGTH_TABLE[sym - 257];
							run = temp >>> 3;
							int numExtraBits = temp & 7;
							run += (int)inputBitBuffer & ((1 << numExtraBits) - 1);
							inputBitBuffer >>>= numExtraBits;
							inputBitBufferLength -= numExtraBits;
						}
						
						// Decode next distance symbol (a customized version of decodeSymbol())
						if (distanceCodeTree == null)
							throw new DataFormatException("Length symbol encountered with empty distance code");
						final int distSym;
						{
							int temp = distanceCodeTable[(int)inputBitBuffer & CODE_TABLE_MASK];
							assert temp >= 0;  // No need to mask off sign extension bits
							int consumed = temp >>> 11;
							inputBitBuffer >>>= consumed;
							inputBitBufferLength -= consumed;
							int node = (temp << 21) >> 21;  // Sign extension from 11 bits
							while (node >= 0) {  // Medium path
								node = distanceCodeTree[node + ((int)inputBitBuffer & 1)];
								inputBitBuffer >>>= 1;
								inputBitBufferLength--;
							}
							distSym = ~node;
						}
						assert 0 <= distSym && distSym <= 31;
						
						// Decode the distance (a customized version of decodeDistance())
						if (distSym > 29)
							throw new DataFormatException("Reserved distance symbol: " + distSym);
						{
							int temp = DISTANCE_TABLE[distSym];
							dist = temp >>> 4;
							int numExtraBits = temp & 0xF;
							dist += (int)inputBitBuffer & ((1 << numExtraBits) - 1);
							inputBitBuffer >>>= numExtraBits;
							inputBitBufferLength -= numExtraBits;
						}
						
					} else {  // sym == 256, end of block
						isDone = true;
						break;
					}
					
				} else {  // General case (always correct), when not enough bits in buffer to guarantee reading
					int sym = decodeSymbol(literalLengthCodeTree);
					assert 0 <= sym && sym <= 287;
					if (sym < 256) {  // Literal byte
						b[index] = (byte)sym;
						index++;
						dictionary[dictionaryIndex] = (byte)sym;
						dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
						continue;
					} else if (sym > 256) {  // Length and distance for copying
						run = decodeRunLength(sym);
						if (distanceCodeTree == null)
							throw new DataFormatException("Length symbol encountered with empty distance code");
						int distSym = decodeSymbol(distanceCodeTree);
						assert 0 <= distSym && distSym <= 31;
						dist = decodeDistance(distSym);
					} else {  // sym == 256, end of block
						isDone = true;
						break;
					}
				}
				
				// Copy bytes to output and dictionary
				assert 3 <= run && run <= MAX_RUN_LENGTH;
				assert 1 <= dist && dist <= 32768;
				int dictReadIndex = (dictionaryIndex - dist) & DICTIONARY_MASK;
				if (run <= end - index) {  // Nice case with less branching
					for (int i = 0; i < run; i++) {
						byte bb = dictionary[dictReadIndex];
						dictReadIndex = (dictReadIndex + 1) & DICTIONARY_MASK;
						dictionary[dictionaryIndex] = bb;
						dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
						b[index] = bb;
						index++;
					}
				} else {  // General case
					for (int i = 0; i < run; i++) {
						byte bb = dictionary[dictReadIndex];
						dictReadIndex = (dictReadIndex + 1) & DICTIONARY_MASK;
						dictionary[dictionaryIndex] = bb;
						dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
						if (index < end) {
							b[index] = bb;
							index++;
						} else
							numPendingOutputBytes++;
					}
				}
			}
			return index - off;
		}
		
		
		public boolean isDone() {
			return isDone;
		}
		
		
		/*---- Huffman coding methods ----*/
		
		// Reads bits from the input buffers/stream and uses the given code tree to
		// decode the next symbol. The returned symbol value is a non-negative integer.
		// This throws an IOException if the end of stream is reached before a symbol
		// is decoded, or if the underlying stream experiences an I/O exception.
		private int decodeSymbol(short[] codeTree) throws IOException {
			int node = 0;  // An index into the codeTree array which signifies the current tree node
			while (node >= 0) {
				if (inputBitBufferLength > 0) {  // Medium path using buffered bits
					node = codeTree[node + ((int)inputBitBuffer & 1)];
					inputBitBuffer >>>= 1;
					inputBitBufferLength--;
				} else  // Slow path with potential I/O operations
					node = codeTree[node + readBits(1)];
			}
			return ~node;  // Symbol was encoded as bitwise complement
		}
		
		
		// Takes the given run length symbol in the range [257, 287], possibly
		// reads some more input bits, and returns a number in the range [3, 258].
		// This throws an IOException if bits needed to be read but the end of
		// stream was reached or the underlying stream experienced an I/O exception.
		private int decodeRunLength(int sym) throws IOException {
			assert 257 <= sym && sym <= 287;
			if (sym <= 264)
				return sym - 254;
			else if (sym <= 284) {
				int numExtraBits = (sym - 261) >>> 2;
				return ((((sym - 1) & 3) | 4) << numExtraBits) + 3 + readBits(numExtraBits);
			} else if (sym == 285)
				return 258;
			else  // sym is 286 or 287
				throw new DataFormatException("Reserved run length symbol: " + sym);
		}
		
		
		// Takes the given distance symbol in the range [0, 31], possibly reads
		// some more input bits, and returns a number in the range [1, 32768].
		// This throws an IOException if bits needed to be read but the end of
		// stream was reached or the underlying stream experienced an I/O exception.
		private int decodeDistance(int sym) throws IOException {
			assert 0 <= sym && sym <= 31;
			if (sym <= 3)
				return sym + 1;
			else if (sym <= 29) {
				int numExtraBits = (sym >>> 1) - 1;
				return (((sym & 1) | 2) << numExtraBits) + 1 + readBits(numExtraBits);
			} else  // sym is 30 or 31
				throw new DataFormatException("Reserved distance symbol: " + sym);
		}
		
		
		/* 
		 * Converts the given array of symbol code lengths into a canonical code tree.
		 * A symbol code length is either zero (absent from the tree) or a positive integer.
		 * 
		 * A code tree is an array of integers, where each pair represents a node.
		 * Each pair is adjacent and starts on an even index. The earlier element of
		 * the pair represents the left child and the later element represents the
		 * right child. The root node is at index 0. If an element is non-negative,
		 * then it is the index of the child node in the array. Otherwise it is the
		 * bitwise complement of the leaf symbol. This tree is used in decodeSymbol()
		 * and codeTreeToCodeTable(). Not every element of the array needs to be
		 * used, nor do used elements need to be contiguous.
		 * 
		 * For example, this Huffman tree:
		 *          /\
		 *         0  1
		 *        /    \
		 *       /\    'c'
		 *      0  1
		 *     /    \
		 *   'a'    'b'
		 * is serialized as this array:
		 *   [2, ~'c', ~'a', ~'b']
		 * because the root is located at index 0 and
		 * the other internal node is located at index 2.
		 */
		private static short[] codeLengthsToCodeTree(byte[] codeLengths) throws DataFormatException {
			final short CODE_TREE_UNUSED_SLOT = 0x7000;
			final short CODE_TREE_OPEN_SLOT   = 0x7002;
			
			if (codeLengths.length < 2)
				throw new IllegalArgumentException("This canonical code produces an under-full Huffman code tree");
			if (codeLengths.length > 16385)  // Because some indexes would overflow int16
				throw new IllegalArgumentException("Too many codes");
			
			// Allocate array for the worst case if all symbols are present
			var result = new short[(codeLengths.length - 1) * 2];
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
						throw new DataFormatException("This canonical code produces an over-full Huffman code tree");
					
					// Put the symbol into the slot and increment
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
			
			// Check for unused open slots after all symbols are allocated
			for (int i = 0; i < allocated; i++) {
				if (result[i] == CODE_TREE_OPEN_SLOT)
					throw new DataFormatException("This canonical code produces an under-full Huffman code tree");
			}
			return result;
		}
		
		
		/* 
		 * Converts a code tree array into a fast look-up table that consumes up to
		 * CODE_TABLE_BITS at once. Each entry i in the table encodes the result of
		 * decoding starting from the root and consuming the bits of i starting from
		 * the lowest-order bits.
		 * 
		 * Each array element encodes (numBitsConsumed << 11) | (node & ((1<<11)-1), where:
		 * - numBitsConsumed is a 4-bit unsigned integer in the range [1, CODE_TABLE_BITS].
		 * - node is an 11-bit signed integer representing either the current node
		 *   (which is a non-negative number) after consuming all the available bits
		 *   from i, or the bitwise complement of the decoded symbol (so it's negative).
		 * Note that each element is a non-negative number.
		 */
		private static short[] codeTreeToCodeTable(short[] codeTree) {
			assert 1 <= CODE_TABLE_BITS && CODE_TABLE_BITS <= 15;
			var result = new short[1 << CODE_TABLE_BITS];
			for (int i = 0; i < result.length; i++) {
				// Simulate decodeSymbol() using the bits of i
				int node = 0;
				int consumed = 0;
				do {
					assert node % 2 == 0;
					node = codeTree[node + ((i >>> consumed) & 1)];
					consumed++;
				} while (node >= 0 && consumed < CODE_TABLE_BITS);
				
				assert 1 <= consumed && consumed <= 15;  // uint4
				assert -1024 <= node && node <= 1023;  // int11
				result[i] = (short)(consumed << 11 | (node & 0x7FF));
				assert result[i] >= 0;
			}
			return result;
		}
		
		
		/*---- Constants and tables ----*/
		
		private static final int[] CODE_LENGTH_CODE_ORDER =
			{16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
		
		private static final short[] FIXED_LITERAL_LENGTH_CODE_TREE;
		private static final short[] FIXED_LITERAL_LENGTH_CODE_TABLE;
		private static final short[] FIXED_DISTANCE_CODE_TREE;
		private static final short[] FIXED_DISTANCE_CODE_TABLE;
		
		// Any integer from 1 to 15 is valid. Affects speed but produces same output.
		private static final int CODE_TABLE_BITS = 9;
		private static final int CODE_TABLE_MASK = (1 << CODE_TABLE_BITS) - 1;
		
		static {
			if (!(1 <= CODE_TABLE_BITS && CODE_TABLE_BITS <= 15))
				throw new AssertionError("Value out of range");
		}
		
		
		static {
			var llcodelens = new byte[288];
			Arrays.fill(llcodelens,   0, 144, (byte)8);
			Arrays.fill(llcodelens, 144, 256, (byte)9);
			Arrays.fill(llcodelens, 256, 280, (byte)7);
			Arrays.fill(llcodelens, 280, 288, (byte)8);
			
			var distcodelens = new byte[32];
			Arrays.fill(distcodelens, (byte)5);
			
			try {
				FIXED_LITERAL_LENGTH_CODE_TREE = codeLengthsToCodeTree(llcodelens);
				FIXED_DISTANCE_CODE_TREE = codeLengthsToCodeTree(distcodelens);
			} catch (DataFormatException e) {
				throw new AssertionError(e);
			}
			FIXED_LITERAL_LENGTH_CODE_TABLE = codeTreeToCodeTable(FIXED_LITERAL_LENGTH_CODE_TREE);
			FIXED_DISTANCE_CODE_TABLE = codeTreeToCodeTable(FIXED_DISTANCE_CODE_TREE);
		}
		
		
		private static final int MAX_RUN_LENGTH = 258;  // Required by the specification, do not modify
		
		static {
			if (MAX_RUN_LENGTH - 1 > DICTIONARY_LENGTH)
				throw new AssertionError("Cannot guarantee all pending run bytes can be buffered in dictionary");
		}
		
		
		// For length symbols from 257 to 285 (inclusive). RUN_LENGTH_TABLE[i]
		// = (base of run length) << 3 | (number of extra bits to read).
		private static final short[] RUN_LENGTH_TABLE = {
			  3 << 3 | 0,
			  4 << 3 | 0,
			  5 << 3 | 0,
			  6 << 3 | 0,
			  7 << 3 | 0,
			  8 << 3 | 0,
			  9 << 3 | 0,
			 10 << 3 | 0,
			 11 << 3 | 1,
			 13 << 3 | 1,
			 15 << 3 | 1,
			 17 << 3 | 1,
			 19 << 3 | 2,
			 23 << 3 | 2,
			 27 << 3 | 2,
			 31 << 3 | 2,
			 35 << 3 | 3,
			 43 << 3 | 3,
			 51 << 3 | 3,
			 59 << 3 | 3,
			 67 << 3 | 4,
			 83 << 3 | 4,
			 99 << 3 | 4,
			115 << 3 | 4,
			131 << 3 | 5,
			163 << 3 | 5,
			195 << 3 | 5,
			227 << 3 | 5,
			258 << 3 | 0,
		};
		
		// For length symbols from 0 to 29 (inclusive). DISTANCE_TABLE[i]
		// = (base of distance) << 4 | (number of extra bits to read).
		private static final int[] DISTANCE_TABLE = {
			   0x1 << 4 |  0,
			   0x2 << 4 |  0,
			   0x3 << 4 |  0,
			   0x4 << 4 |  0,
			   0x5 << 4 |  1,
			   0x7 << 4 |  1,
			   0x9 << 4 |  2,
			   0xD << 4 |  2,
			  0x11 << 4 |  3,
			  0x19 << 4 |  3,
			  0x21 << 4 |  4,
			  0x31 << 4 |  4,
			  0x41 << 4 |  5,
			  0x61 << 4 |  5,
			  0x81 << 4 |  6,
			  0xC1 << 4 |  6,
			 0x101 << 4 |  7,
			 0x181 << 4 |  7,
			 0x201 << 4 |  8,
			 0x301 << 4 |  8,
			 0x401 << 4 |  9,
			 0x601 << 4 |  9,
			 0x801 << 4 | 10,
			 0xC01 << 4 | 10,
			0x1001 << 4 | 11,
			0x1801 << 4 | 11,
			0x2001 << 4 | 12,
			0x3001 << 4 | 12,
			0x4001 << 4 | 13,
			0x6001 << 4 | 13,
		};
		
	}
	
}
