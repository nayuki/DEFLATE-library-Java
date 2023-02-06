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
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;
import io.nayuki.deflate.DataFormatException;
import io.nayuki.deflate.DataFormatException.Reason;


public final class Open implements State {
	
	/*---- Fields ----*/
	
	// The underlying stream to read from
	public final InputStream input;
	
	// Indicates whether mark() should be called when the underlying
	// input stream is read, and whether calling detach() is allowed.
	private final boolean endExactly;
	
	
	// The typical data flow in this decompressor looks like:
	//   input (the underlying input stream) -> input.read()
	//   -> inputBuffer -> getLong()
	//   -> inputButBuffer1 -> packing logic in readBits()
	//   -> inputBitBuffer0 -> readBit() or equivalent
	//   -> Huffman decoding logic for literal and length-distance symbols
	//   -> LZ77 decoding logic -> dictionary
	//   -> copying to the caller's array
	//   -> b (the array passed into this.read(byte[],int,int)).
	
	// Buffer of bytes read from input.read() (the underlying input stream)
	private final ByteBuffer inputBuffer;  // Can have any positive length (but longer means less overhead)
	
	// Buffer of bits packed from the bytes in `inputBuffer`
	private long inputBitBuffer0 = 0;       // Always in the range [0, 2^inputBitBuffer0Length)
	private int inputBitBuffer0Length = 0;  // Always in the range [0, 64]
	
	private long inputBitBuffer1 = 0;       // Always in the range [0, 2^inputBitBuffer1Length)
	private int inputBitBuffer1Length = 0;  // Always in the range [0, 64]
	
	
	private Optional<BlockDecoder> blockDecoder = Optional.empty();
	
	// Indicates whether a block header with the `bfinal` flag has been seen.
	// This starts as false, should eventually become true, and never changes back to false.
	private boolean isLastBlock = false;
	
	
	// Buffer of last 32 KiB of decoded data, for LZ77 decompression
	private final byte[] dictionary = new byte[DICTIONARY_LENGTH];
	private int dictionaryIndex = 0;  // Always in the range [0, dictionary.length)
	private int dictionaryLength = 0;  // Number of bytes written, in the range [0, dictionary.length], saturating at the maximum
	
	
	
	/*---- Constructor ----*/
	
	public Open(InputStream in, boolean endExact, int inBufLen) {
		input = in;
		endExactly = endExact;
		inputBuffer = ByteBuffer.allocate(inBufLen)
			.order(ByteOrder.LITTLE_ENDIAN).position(0).limit(0);
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
					case 3 -> throw new DataFormatException(Reason.RESERVED_BLOCK_TYPE, "Reserved block type");
					default -> throw new AssertionError("Unreachable value");
				});
			}
			
			BlockDecoder dec = blockDecoder.get();
			result += dec.read(b, off + result, len - result);
			if (dec.isDone()) {
				blockDecoder = Optional.empty();
				if (isLastBlock && endExactly)
					finish();
			}
		}
		return (result > 0 || blockDecoder.isPresent() || !isLastBlock) ? result : -1;
	}
	
	
	private void finish() throws IOException {
		// Rewind the underlying stream, then skip over bytes that were already consumed.
		// Note that a byte with some bits consumed is considered to be fully consumed.
		input.reset();
		int skip = inputBuffer.position() - (inputBitBuffer0Length + inputBitBuffer1Length) / 8;
		assert skip >= 0;
		try {
			new DataInputStream(input).skipNBytes(skip);
		} catch (EOFException e) {
			DataFormatException.throwUnexpectedEnd();
		}
	}
	
	
	public void close() throws IOException {
		input.close();
	}
	
	
	
	/*---- Private methods ----*/
	
	// Returns the given number of least significant bits from the bit buffer.
	// This updates the bit buffer state and possibly also the byte buffer state.
	private int readBits(int numBits) throws IOException {
		// Check arguments and invariants
		assert 0 <= numBits && numBits <= 16;  // Note: DEFLATE uses up to 16, but this method is correct up to 31
		assert isBitBufferValid();
		
		// Ensure there is enough data in the bit buffer to satisfy the request
		while (inputBitBuffer0Length < numBits) {
			if (inputBitBuffer1Length > 0) {
				int n = Math.min(64 - inputBitBuffer0Length, inputBitBuffer1Length);
				inputBitBuffer0 |= inputBitBuffer1 << inputBitBuffer0Length;
				inputBitBuffer0Length += n;
				inputBitBuffer1 >>>= n;
				inputBitBuffer1Length -= n;
			} else {
				if (!inputBuffer.hasRemaining())
					fillInputBuffer();
				
				// Pack as many bytes as possible from input byte buffer into the bit buffer
				int numBytes = Math.min((64 - inputBitBuffer0Length) >>> 3, inputBuffer.remaining());
				assert 0 <= numBytes && numBytes <= 8;
				for (int i = 0; i < numBytes; i++, inputBitBuffer0Length += 8)
					inputBitBuffer0 |= (inputBuffer.get() & 0xFFL) << inputBitBuffer0Length;
				assert isBitBufferValid();
			}
		}
		
		// Extract the bits to return
		int result = (int)inputBitBuffer0 & ((1 << numBits) - 1);
		assert result >>> numBits == 0;
		inputBitBuffer0 >>>= numBits;
		inputBitBuffer0Length -= numBits;
		assert isBitBufferValid();
		return result;
	}
	
	
	private boolean isBitBufferValid() {
		return 0 <= inputBitBuffer0Length && inputBitBuffer0Length <= 64
			&& (inputBitBuffer0Length == 64 || inputBitBuffer0 >>> inputBitBuffer0Length == 0);
	}
	
	
	// Fills the empty input byte buffer with at least
	// one new byte read from the underlying input stream.
	private void fillInputBuffer() throws IOException {
		assert !inputBuffer.hasRemaining();
		if (endExactly)
			input.mark(inputBuffer.capacity());
		int n = input.read(inputBuffer.array());
		if (n == -1)
			DataFormatException.throwUnexpectedEnd();
		else if (n == 0)
			throw new AssertionError("read() returned zero bytes");
		else
			inputBuffer.position(0).limit(n);
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
			// Discard bits to align to byte
			readBits((inputBitBuffer0Length + inputBitBuffer1Length) % 8);
			assert (inputBitBuffer0Length + inputBitBuffer1Length) % 8 == 0;
			
			numRemainingBytes = readBits(16);
			assert 0x0000 <= numRemainingBytes && numRemainingBytes <= 0xFFFF;
			if (numRemainingBytes != (readBits(16) ^ 0xFFFF))
				throw new DataFormatException(Reason.UNCOMPRESSED_BLOCK_LENGTH_MISMATCH, "len/nlen mismatch in uncompressed block");
		}
		
		
		public int read(byte[] b, final int off, int len) throws IOException {
			if (numRemainingBytes < 0)
				throw new AssertionError("Unreachable state");
			
			// Check bit buffer invariants
			assert isBitBufferValid();
			assert (inputBitBuffer0Length + inputBitBuffer1Length) % 8 == 0;
			
			len = Math.min(numRemainingBytes, len);
			numRemainingBytes -= len;
			int index = off;
			final int end = off + len;
			assert off <= end && end <= b.length;
			
			// First unpack saved bits
			for (; inputBitBuffer0Length + inputBitBuffer1Length >= 8 && index < end; index++)
				b[index] = (byte)readBits(8);
			
			// Copy from input buffer
			{
				int n = Math.min(end - index, inputBuffer.remaining());
				assert inputBitBuffer0Length + inputBitBuffer1Length == 0 || n == 0;
				inputBuffer.get(b, index, n);
				index += n;
			}
			
			// Read directly from input stream, bypassing the input buffer
			if (index < end) {
				assert inputBitBuffer0Length + inputBitBuffer1Length == 0 && !inputBuffer.hasRemaining();
				if (endExactly) {
					inputBuffer.position(0).limit(0);
					input.mark(0);
				}
				do {
					int n = input.read(b, index, end - index);
					if (n == -1)
						DataFormatException.throwUnexpectedEnd();
					index += n;
				} while (index < end);
				if (endExactly)
					input.mark(0);
			}
			
			// Copy output bytes to dictionary
			for (index = off; index < end; ) {
				int n = Math.min(end - index, dictionary.length - dictionaryIndex);
				System.arraycopy(b, index, dictionary, dictionaryIndex, n);
				index += n;
				dictionaryIndex = (dictionaryIndex + n) & DICTIONARY_MASK;
			}
			dictionaryLength += Math.min(len, dictionary.length - dictionaryLength);
			
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
		private final int maxBitsPerIteration;  // In the range [1, 48]
		
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
				for (int i = 0; i < codeLens.length; ) {
					int sym = decodeSymbol(codeLenCodeTree);
					assert 0 <= sym && sym < codeLenCodeLen.length;
					if (sym < 16) {
						runVal = (byte)sym;
						codeLens[i] = runVal;
						i++;
					} else {
						int runLen = switch (sym) {
							case 16 -> {
								if (runVal == -1)
									throw new DataFormatException(Reason.NO_PREVIOUS_CODE_LENGTH_TO_COPY, "No code length value to copy");
								yield readBits(2) + 3;
							}
							case 17 -> {
								runVal = 0;
								yield readBits(3) + 3;
							}
							case 18 -> {
								runVal = 0;
								yield readBits(7) + 11;
							}
							default -> throw new AssertionError("Unreachable value");
						};
						for (; runLen > 0; runLen--, i++) {
							if (i >= codeLens.length)
								throw new DataFormatException(Reason.CODE_LENGTH_CODE_OVER_FULL, "Run exceeds number of codes");
							codeLens[i] = runVal;
						}
					}
				}
				
				// Create literal-length code tree
				byte[] litLenCodeLen = Arrays.copyOf(codeLens, numLitLenCodes);
				if (litLenCodeLen[256] == 0)
					throw new DataFormatException(Reason.END_OF_BLOCK_CODE_ZERO_LENGTH, "End-of-block symbol has zero code length");
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
			
			if (!(1 <= maxBitsPerIteration && maxBitsPerIteration <= 48))
				throw new AssertionError("Unreachable value");
		}
		
		
		public int read(byte[] b, final int off, final int len) throws IOException {
			int index = off;
			final int end = off + len;
			assert off <= end && end <= b.length;
			
			for (; numPendingOutputBytes > 0 && index < end; numPendingOutputBytes--, index++)
				b[index] = dictionary[(dictionaryIndex - numPendingOutputBytes) & DICTIONARY_MASK];
			
			while (index < end) {
				assert numPendingOutputBytes == 0;
				assert isBitBufferValid();
				
				// Try to fill the input bit buffer (somewhat similar to logic in readBits())
				if (inputBitBuffer0Length < maxBitsPerIteration) {
					if (inputBitBuffer1Length > 0) {
						int n = Math.min(64 - inputBitBuffer0Length, inputBitBuffer1Length);
						inputBitBuffer0 |= inputBitBuffer1 << inputBitBuffer0Length;
						inputBitBuffer0Length += n;
						inputBitBuffer1 >>>= n;
						inputBitBuffer1Length -= n;
					}
					if (inputBitBuffer0Length < maxBitsPerIteration) {
						assert inputBitBuffer1Length == 0;
						if (inputBuffer.remaining() >= 8) {
							inputBitBuffer1 = inputBuffer.getLong();
							inputBitBuffer1Length = 64;
							int n = Math.min(64 - inputBitBuffer0Length, inputBitBuffer1Length);
							inputBitBuffer0 |= inputBitBuffer1 << inputBitBuffer0Length;
							inputBitBuffer0Length += n;
							inputBitBuffer1 >>>= n;
							inputBitBuffer1Length -= n;
						} else {
							for (; inputBitBuffer0Length <= 56 && inputBuffer.hasRemaining(); inputBitBuffer0Length += 8)
								inputBitBuffer0 |= (inputBuffer.get() & 0xFFL) << inputBitBuffer0Length;
						}
					}
					assert isBitBufferValid();
				}
				
				int run, dist;
				
				if (inputBitBuffer0Length >= maxBitsPerIteration) {  // Fast path entirely from bit buffer
					// Decode next literal/length symbol (a customized version of decodeSymbol())
					final int sym;
					{
						int temp = literalLengthCodeTable[(int)inputBitBuffer0 & CODE_TABLE_MASK];
						int consumed = temp & 0xF;
						inputBitBuffer0 >>>= consumed;
						inputBitBuffer0Length -= consumed;
						int node = temp >> 4;
						while (node >= 0) {
							node = literalLengthCodeTree[node + ((int)inputBitBuffer0 & 1)];
							inputBitBuffer0 >>>= 1;
							inputBitBuffer0Length--;
						}
						sym = ~node;
						assert isBitBufferValid();
					}
					
					// Handle the symbol by ranges
					assert 0 <= sym && sym <= 287;
					if (sym < 256) {  // Literal byte
						b[index] = (byte)sym;
						index++;
						dictionary[dictionaryIndex] = (byte)sym;
						dictionaryIndex = (dictionaryIndex + 1) & DICTIONARY_MASK;
						if (dictionaryLength < dictionary.length)
							dictionaryLength++;
						continue;
						
					} else if (sym > 256) {  // Length and distance for copying
						// Decode the run length (a customized version of decodeRunLength())
						assert 257 <= sym && sym <= 287;
						{
							int temp;
							try {
								temp = RUN_LENGTH_TABLE[sym - 257];
							} catch (ArrayIndexOutOfBoundsException e) {
								throw new DataFormatException(Reason.RESERVED_LENGTH_SYMBOL, "Reserved run length symbol: " + sym);
							}
							run = temp >>> 3;
							int numExtraBits = temp & 7;
							run += (int)inputBitBuffer0 & ((1 << numExtraBits) - 1);
							inputBitBuffer0 >>>= numExtraBits;
							inputBitBuffer0Length -= numExtraBits;
						}
						
						// Decode next distance symbol (a customized version of decodeSymbol())
						if (distanceCodeTree == null)
							throw new DataFormatException(Reason.LENGTH_ENCOUNTERED_WITH_EMPTY_DISTANCE_CODE, "Length symbol encountered with empty distance code");
						final int distSym;
						{
							int temp = distanceCodeTable[(int)inputBitBuffer0 & CODE_TABLE_MASK];
							int consumed = temp & 0xF;
							inputBitBuffer0 >>>= consumed;
							inputBitBuffer0Length -= consumed;
							int node = temp >> 4;
							while (node >= 0) {
								node = distanceCodeTree[node + ((int)inputBitBuffer0 & 1)];
								inputBitBuffer0 >>>= 1;
								inputBitBuffer0Length--;
							}
							distSym = ~node;
						}
						
						// Decode the distance (a customized version of decodeDistance())
						assert 0 <= distSym && distSym <= 31;
						{
							int temp;
							try {
								temp = DISTANCE_TABLE[distSym];
							} catch (ArrayIndexOutOfBoundsException e) {
								throw new DataFormatException(Reason.RESERVED_DISTANCE_SYMBOL, "Reserved distance symbol: " + distSym);
							}
							dist = temp >>> 4;
							int numExtraBits = temp & 0xF;
							dist += (int)inputBitBuffer0 & ((1 << numExtraBits) - 1);
							inputBitBuffer0 >>>= numExtraBits;
							inputBitBuffer0Length -= numExtraBits;
						}
						assert isBitBufferValid();
						
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
						if (dictionaryLength < dictionary.length)
							dictionaryLength++;
						continue;
					} else if (sym > 256) {  // Length and distance for copying
						run = decodeRunLength(sym);
						if (distanceCodeTree == null)
							throw new DataFormatException(Reason.LENGTH_ENCOUNTERED_WITH_EMPTY_DISTANCE_CODE, "Length symbol encountered with empty distance code");
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
				if (dist > dictionaryLength)
					throw new DataFormatException(Reason.COPY_FROM_BEFORE_DICTIONARY_START, "Attempting to copy from before start of dictionary");
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
				dictionaryLength += Math.min(run, dictionary.length - dictionaryLength);
			}
			return index - off;
		}
		
		
		public boolean isDone() {
			return numPendingOutputBytes == 0 && isDone;
		}
		
		
		/*---- Huffman coding methods ----*/
		
		// Reads bits from the input buffers/stream and uses the given code tree to
		// decode the next symbol. The returned symbol value is a non-negative integer.
		// This throws an IOException if the end of stream is reached before a symbol
		// is decoded, or if the underlying stream experiences an I/O exception.
		private int decodeSymbol(short[] codeTree) throws IOException {
			int node = 0;  // An index into the codeTree array which signifies the current tree node
			while (node >= 0) {
				if (inputBitBuffer0Length > 0) {  // Medium path using buffered bits
					node = codeTree[node + ((int)inputBitBuffer0 & 1)];
					inputBitBuffer0 >>>= 1;
					inputBitBuffer0Length--;
				} else  // Slow path with potential I/O operations
					node = codeTree[node + readBits(1)];
			}
			assert isBitBufferValid();
			return ~node;  // Symbol was encoded as bitwise complement
		}
		
		
		// Takes the given run length symbol in the range [257, 287], possibly
		// reads some more input bits, and returns a number in the range [3, 258].
		// This throws an IOException if bits needed to be read but the end of
		// stream was reached or the underlying stream experienced an I/O exception.
		private int decodeRunLength(int sym) throws IOException {
			assert 257 <= sym && sym <= 287;
			try {
				int temp = RUN_LENGTH_TABLE[sym - 257];
				return (temp >>> 3) + readBits(temp & 7);
			} catch (ArrayIndexOutOfBoundsException e) {
				throw new DataFormatException(Reason.RESERVED_LENGTH_SYMBOL, "Reserved run length symbol: " + sym);
			}
		}
		
		
		// Takes the given distance symbol in the range [0, 31], possibly reads
		// some more input bits, and returns a number in the range [1, 32768].
		// This throws an IOException if bits needed to be read but the end of
		// stream was reached or the underlying stream experienced an I/O exception.
		private int decodeDistance(int sym) throws IOException {
			assert 0 <= sym && sym <= 31;
			try {
				int temp = DISTANCE_TABLE[sym];
				return (temp >>> 4) + readBits(temp & 0xF);
			} catch (ArrayIndexOutOfBoundsException e) {
				throw new DataFormatException(Reason.RESERVED_DISTANCE_SYMBOL, "Reserved distance symbol: " + sym);
			}
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
			var codeLengthsAndSymbols = new short[codeLengths.length];
			for (int i = 0; i < codeLengths.length; i++) {
				byte cl = codeLengths[i];
				if (cl < 0)
					throw new IllegalArgumentException("Negative code length");
				if (cl > 15)
					throw new AssertionError("Maximum code length exceeds DEFLATE specification");
				int pair = cl << 11 | i;  // uint15
				assert pair >>> 15 == 0;
				codeLengthsAndSymbols[i] = (short)pair;
			}
			Arrays.sort(codeLengthsAndSymbols);
			
			int codeLenSymIndex = 0;
			// Skip unused symbols (code length 0)
			while (codeLenSymIndex < codeLengthsAndSymbols.length && codeLengthsAndSymbols[codeLenSymIndex] >>> 11 == 0)
				codeLenSymIndex++;
			
			int numCodes = codeLengthsAndSymbols.length - codeLenSymIndex;
			if (numCodes < 2)
				throw new DataFormatException(Reason.HUFFMAN_CODE_UNDER_FULL, "This canonical code produces an under-full Huffman code tree");
			if (numCodes > 16385)  // Because some indexes would overflow int16
				throw new IllegalArgumentException("Too many codes");
			
			var result = new short[(numCodes - 1) * 2];
			int resultNext = 0;
			int resultEnd = 2;  // Start with root node already allocated; always even
			int curCodeLen = 1;
			for (; codeLenSymIndex < codeLengthsAndSymbols.length; codeLenSymIndex++) {
				int pair = codeLengthsAndSymbols[codeLenSymIndex];
				for (int codeLen = pair >>> 11; curCodeLen < codeLen; curCodeLen++) {
					// Double every open slot
					for (int end = resultEnd; resultNext < end; resultNext++) {
						if (resultEnd >= result.length)
							throw new DataFormatException(Reason.HUFFMAN_CODE_UNDER_FULL, "This canonical code produces an under-full Huffman code tree");
						result[resultNext] = (short)resultEnd;
						resultEnd += 2;
					}
				}
				if (resultNext >= resultEnd)
					throw new DataFormatException(Reason.HUFFMAN_CODE_OVER_FULL, "This canonical code produces an over-full Huffman code tree");
				int symbol = pair & ((1 << 11) - 1);
				result[resultNext] = (short)~symbol;
				resultNext++;
			}
			if (resultEnd != result.length)
				throw new AssertionError("Unreachable state");
			if (resultNext < resultEnd)
				throw new DataFormatException(Reason.HUFFMAN_CODE_UNDER_FULL, "This canonical code produces an under-full Huffman code tree");
			return result;
		}
		
		
		/* 
		 * Converts a code tree array into a fast look-up table that consumes up to
		 * CODE_TABLE_BITS at once. Each entry i in the table encodes the result of
		 * decoding starting from the root and consuming the bits of i starting from
		 * the lowest-order bits.
		 * 
		 * Each array element encodes (node << 4) | numBitsConsumed, where:
		 * - numBitsConsumed is a 4-bit unsigned integer in the range [1, CODE_TABLE_BITS].
		 * - node is an 12-bit signed integer representing either the current node
		 *   (which is a non-negative number) after consuming all the available bits
		 *   from i, or the bitwise complement of the decoded symbol (so it's negative).
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
				assert -2048 <= node && node <= 2047;  // int12
				result[i] = (short)(node << 4 | consumed);
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
		private static final short[] RUN_LENGTH_TABLE = new short[29];
		
		static {
			for (int i = 0; i < RUN_LENGTH_TABLE.length; i++) {
				int sym = i + 257;
				int run, extraBits;
				if (sym <= 264) {
					extraBits = 0;
					run = sym - 254;
				} else if (sym <= 284) {
					extraBits = (sym - 261) / 4;
					run = (((sym - 1) % 4 + 4) << extraBits) + 3;
				} else if (sym == 285) {
					extraBits = 0;
					run = 258;
				} else
					throw new AssertionError("Unreachable value");
				assert run >>> 12 == 0;
				assert extraBits >>> 3 == 0;
				RUN_LENGTH_TABLE[i] = (short)(run << 3 | extraBits);
			}
		}
		
		
		// For length symbols from 0 to 29 (inclusive). DISTANCE_TABLE[i]
		// = (base of distance) << 4 | (number of extra bits to read).
		private static final int[] DISTANCE_TABLE = new int[30];
		
		static {
			for (int sym = 0; sym < DISTANCE_TABLE.length; sym++) {
				int dist, extraBits;
				if (sym <= 3) {
					extraBits = 0;
					dist = sym + 1;
				} else if (sym <= 29) {
					extraBits = sym / 2 - 1;
					dist = ((sym % 2 + 2) << extraBits) + 1;
				} else
					throw new AssertionError("Unreachable value");
				assert dist >>> 27 == 0;
				assert extraBits >>> 4 == 0;
			DISTANCE_TABLE[sym] = dist << 4 | extraBits;
			}
		}
		
	}
	
}
