/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;


public enum DynamicHuffmanRle implements Strategy {
	
	SINGLETON;
	
	
	@Override public Decision decide(byte[] b, int off, int historyLen, int dataLen) {
		return new Decision() {
			private final long bitLength;
			{
				var temp = new CountingBitOutputStream();
				try {
					compressTo(temp, false);
				} catch (IOException e) {
					throw new AssertionError(e);
				}
				bitLength = temp.getBitLength();
			}
			
			
			@Override public long getBitLength() {
				return bitLength;
			}
			
			@Override public int getBitPositionBeforeAligningToByte() {
				return -1;
			}
			
			
			@Override public void compressTo(BitOutputStream out, boolean isFinal) throws IOException {
				int index = off + historyLen;
				final int end = index + dataLen;
				
				int[] distCodeLen = {0};
				var histogram = new int[286];
				while (index < end) {
					if (index > off) {
						byte prev = b[index - 1];
						int runLen = 0;
						for (; runLen < MAX_RUN_LENGTH && index + runLen < end && b[index + runLen] == prev; runLen++);
						if (runLen >= MIN_RUN_LENGTH) {
							int sym;
							if (runLen < 11)
								sym = (runLen - 3) / 1 + 257;
							else if (runLen < 19)
								sym = (runLen - 11) / 2 + 265;
							else if (runLen < 35)
								sym = (runLen - 19) / 4 + 269;
							else if (runLen < 67)
								sym = (runLen - 35) / 8 + 273;
							else if (runLen < 131)
								sym = (runLen - 67) / 16 + 277;
							else if (runLen < 258)
								sym = (runLen - 131) / 32 + 281;
							else if (runLen == 258)
								sym = 285;
							else
								throw new AssertionError("Unreachable value");
							histogram[sym]++;
							distCodeLen[0] = 1;
							index += runLen;
							continue;
						}
					}
					histogram[b[index] & 0xFF]++;
					index++;
				}
				histogram[256]++;
				if (dataLen == 0)
					histogram[0]++;  // Dummy value to fill the Huffman code tree
				int[] litLenCodeLen = calcHuffmanCodeLengths(histogram, 15);
				
				var codeLens = new int[litLenCodeLen.length + distCodeLen.length];
				System.arraycopy(litLenCodeLen, 0, codeLens, 0, litLenCodeLen.length);
				System.arraycopy(distCodeLen, 0, codeLens, litLenCodeLen.length, distCodeLen.length);
				
				List<Integer> codeLengthSymbols = new ArrayList<>();
				List<Integer> extraBits = new ArrayList<>();
				for (int i = 0; i < codeLens.length; ) {
					int val = codeLens[i];
					if (val == 0) {
						int runLength = 1;
						for (; runLength < 138 && i + runLength < codeLens.length
							&& codeLens[i + runLength] == 0; runLength++);
						if (runLength < 3) {
							codeLengthSymbols.add(val);
							i++;
						} else if (runLength < 11) {
							codeLengthSymbols.add(17);
							extraBits.add(runLength - 3);
							i += runLength;
						} else if (runLength < 139) {
							codeLengthSymbols.add(18);
							extraBits.add(runLength - 11);
							i += runLength;
						} else
							throw new AssertionError("Unreachable value");
						continue;
					}
					if (i > 0) {
						int runLength = 0;
						for (; runLength < 6 && i + runLength < codeLens.length
							&& codeLens[i + runLength] == codeLens[i - 1]; runLength++);
						if (runLength >= 3) {
							codeLengthSymbols.add(16);
							extraBits.add(runLength - 3);
							i += runLength;
							continue;
						}
					}
					codeLengthSymbols.add(val);
					i++;
				}
				
				histogram = new int[19];
				for (int sym : codeLengthSymbols)
					histogram[sym]++;
				int[] codeLenCodeLen = calcHuffmanCodeLengths(histogram, 7);
				
				var reordered = new int[codeLenCodeLen.length];
				for (int i = 0; i < reordered.length; i++)
					reordered[i] = codeLenCodeLen[CODE_LENGTH_CODE_ORDER[i]];
				int numCodeLenCodeLens = reordered.length;
				for (; numCodeLenCodeLens > 4 && reordered[numCodeLenCodeLens - 1] == 0; numCodeLenCodeLens--);
				
				out.writeBits(isFinal ? 1 : 0, 1);  // bfinal
				out.writeBits(2, 2);  // btype
				
				out.writeBits(litLenCodeLen.length - 257, 5);  // hlit
				out.writeBits(distCodeLen  .length -   1, 5);  // hdist
				out.writeBits(numCodeLenCodeLens   -   4, 4);  // hclen
				
				for (int i = 0; i < numCodeLenCodeLens; i++)
					out.writeBits(reordered[i], 3);
				
				int[] codeLenCode = codeLengthsToCodes(codeLenCodeLen, 7);
				Iterator<Integer> extraBitsIter = extraBits.iterator();
				for (int sym : codeLengthSymbols) {
					int pair = codeLenCode[sym];
					out.writeBits(pair >>> 4, pair & 0xF);
					if (sym >= 16) {
						out.writeBits(extraBitsIter.next(), switch (sym) {
							case 16 -> 2;
							case 17 -> 3;
							case 18 -> 7;
							default -> throw new AssertionError("Unreachable value");
						});
					}
				}
				if (extraBitsIter.hasNext())
					throw new AssertionError("Unreachable state");
				
				int[] litLenCode = codeLengthsToCodes(litLenCodeLen, 15);
				for (index = off + historyLen; index < end; ) {
					if (index > off) {
						byte prev = b[index - 1];
						int runLen = 0;
						for (; runLen < MAX_RUN_LENGTH && index + runLen < end && b[index + runLen] == prev; runLen++);
						if (runLen >= MIN_RUN_LENGTH) {
							int sym, extra, numExtra;
							if (runLen < 11) {
								sym = (runLen - 3) / 1 + 257;
								extra = 0;
								numExtra = 0;
							} else if (runLen < 19) {
								sym = (runLen - 11) / 2 + 265;
								extra = (runLen - 11) % 2;
								numExtra = 1;
							} else if (runLen < 35) {
								sym = (runLen - 19) / 4 + 269;
								extra = (runLen - 19) % 4;
								numExtra = 2;
							} else if (runLen < 67) {
								sym = (runLen - 35) / 8 + 273;
								extra = (runLen - 35) % 8;
								numExtra = 3;
							} else if (runLen < 131) {
								sym = (runLen - 67) / 16 + 277;
								extra = (runLen - 67) % 16;
								numExtra = 4;
							} else if (runLen < 258) {
								sym = (runLen - 131) / 32 + 281;
								extra = (runLen - 131) % 32;
								numExtra = 5;
							} else if (runLen == 258) {
								sym = 285;
								extra = 0;
								numExtra = 0;
							} else
								throw new AssertionError("Unreachable value");
							int pair = litLenCode[sym];
							out.writeBits(pair >>> 4, pair & 0xF);  // Length code
							out.writeBits(extra, numExtra);
							out.writeBits(0, 1);  // Distance code
							index += runLen;
							continue;
						}
					}
					
					int pair = litLenCode[b[index] & 0xFF];
					out.writeBits(pair >>> 4, pair & 0xF);
					index++;
				}
				
				int pair = litLenCode[256];  // End of block
				out.writeBits(pair >>> 4, pair & 0xF);
			}
		};
	}
	
	
	// Configurable
	private static final int MIN_RUN_LENGTH = 3;
	private static final int MAX_RUN_LENGTH = 258;
	static {
		assert 3 <= MIN_RUN_LENGTH && MIN_RUN_LENGTH <= MAX_RUN_LENGTH && MAX_RUN_LENGTH <= 258;
	}
	
	
	
	private static int[] calcHuffmanCodeLengths(int[] symbolHistogram, int maxLen) {
		List<Leaf> leaves = new ArrayList<>();
		for (int sym = 0; sym < symbolHistogram.length; sym++) {
			int freq = symbolHistogram[sym];
			if (freq > 0)
				leaves.add(new Leaf(freq, sym));
		}
		
		// Package-merge algorithm
		List<Node> nodes = new ArrayList<>();
		for (int i = 0; i < maxLen; i++) {
			nodes.addAll(leaves);
			Collections.sort(nodes);
			List<Node> newNodes = new ArrayList<>();
			for (int j = 0; j + 2 <= nodes.size(); j += 2) {
				Node a = nodes.get(j + 0);
				Node b = nodes.get(j + 1);
				newNodes.add(new InternalNode(a.frequency() + b.frequency(), a, b));
			}
			nodes = newNodes;
		}
		
		var nodeHistogram = new int[symbolHistogram.length];
		for (int i = 0; i < leaves.size() - 1; i++)
			nodes.get(i).countOccurrences(nodeHistogram);
		return nodeHistogram;
	}
	
	
	
	private interface Node extends Comparable<Node> {
		
		public long frequency();
		
		public default int compareTo(Node other) {
			return Long.compare(frequency(), other.frequency());
		}
		
		public void countOccurrences(int[] nodeHistogram);
		
	}
	
	
	private record InternalNode(long frequency, Node... children) implements Node {
		
		public void countOccurrences(int[] nodeHistogram) {
			for (Node node : children)
				node.countOccurrences(nodeHistogram);
		}
		
	}
	
	
	private record Leaf(long frequency, int symbol) implements Node {
		
		public void countOccurrences(int[] nodeHistogram) {
			nodeHistogram[symbol]++;
		}
		
	}
	
	
	
	private static final int[] CODE_LENGTH_CODE_ORDER =
		{16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
	
	
	private static int[] codeLengthsToCodes(int[] codeLengths, int maxCodeLength) {
		if (!(1 <= maxCodeLength && maxCodeLength <= 15))
			throw new IllegalArgumentException("Invalid maximum code length");
		var result = new int[codeLengths.length];
		int nextCode = 0;
		for (int codeLength = 1; codeLength <= maxCodeLength; codeLength++) {
			nextCode <<= 1;
			for (int symbol = 0; symbol < codeLengths.length; symbol++) {
				if (codeLengths[symbol] != codeLength)
					continue;
				if (nextCode >>> codeLength != 0)
					throw new IllegalArgumentException("This canonical code produces an over-full Huffman code tree");
				result[symbol] = Integer.reverse(nextCode) >>> (32 - codeLength) << 4 | codeLength;
				nextCode++;
			}
		}
		if (nextCode != 1 << maxCodeLength)
			throw new IllegalArgumentException("This canonical code produces an under-full Huffman code tree");
		return result;
	}
	
}
