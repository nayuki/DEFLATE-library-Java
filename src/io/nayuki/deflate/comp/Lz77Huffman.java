/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;

import java.io.IOException;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;


public record Lz77Huffman(
		boolean useDynamicHuffmanCodes,
		int searchMinimumRunLength,
		int searchMaximumRunLength,
		int searchMinimumDistance,
		int searchMaximumDistance)
	implements Strategy {
	
	
	public Lz77Huffman {
		int minRun = searchMinimumRunLength;
		int maxRun = searchMaximumRunLength;
		int minDist = searchMinimumDistance;
		int maxDist = searchMaximumDistance;
		if (minRun == 0 && maxRun == 0 && minDist == 0 && maxDist == 0);
		else if (ABSOLUTE_MINIMUM_RUN_LENGTH <= minRun && minRun <= maxRun && maxRun <= ABSOLUTE_MAXIMUM_RUN_LENGTH &&
			ABSOLUTE_MINIMUM_DISTANCE <= minDist && minDist <= maxDist && maxDist <= ABSOLUTE_MAXIMUM_DISTANCE);
		else
			throw new IllegalArgumentException("Invalid minimum/maximum run-length/distance");
	}
	
	
	@Override public Decision decide(byte[] b, int off, int historyLen, int dataLen) {
		return new Decision() {
			private final long bitLength;
			{
				var temp = new CountingBitOutputStream();
				try {
					compressTo(temp, false);
				} catch (IOException e) {
					throw new AssertionError("Caught impossible exception", e);
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
				
				var symbolsAndExtraBits = ShortBuffer.allocate(Math.toIntExact(Math.ceilDiv(dataLen * 4L, 3)));
				var litLenHistogram = new int[286];
				var distHistogram = new int[30];
				while (index < end) {
					int bestRun = 0;
					int bestDist = 0;
					for (int dist = searchMinimumDistance, distEnd = Math.min(searchMaximumDistance, index - off); dist <= distEnd && bestRun < searchMaximumRunLength; dist++) {
						int run = 0;
						int historyIndex = index - dist;
						int dataIndex = index;
						for (; run <= searchMaximumRunLength && dataIndex < end && b[dataIndex] == b[historyIndex]; run++, dataIndex++) {
							historyIndex++;
							if (historyIndex == index)
								historyIndex -= dist;
						}
						if (run > bestRun || run == bestRun && dist < bestDist) {
							bestRun = run;
							bestDist = dist;
						}
					}
					if (bestRun < searchMinimumRunLength) {
						int sym = b[index] & 0xFF;  // Literal
						index++;
						symbolsAndExtraBits.put((short)(sym << 4));
						litLenHistogram[sym]++;
					}
					else {
						{
							int sym, extra, numExtra;
							if      (bestRun <   11) { sym = (bestRun -   3) /  1 + 257;  extra = (bestRun -   3) %  1;  numExtra = 0; }
							else if (bestRun <   19) { sym = (bestRun -  11) /  2 + 265;  extra = (bestRun -  11) %  2;  numExtra = 1; }
							else if (bestRun <   35) { sym = (bestRun -  19) /  4 + 269;  extra = (bestRun -  19) %  4;  numExtra = 2; }
							else if (bestRun <   67) { sym = (bestRun -  35) /  8 + 273;  extra = (bestRun -  35) %  8;  numExtra = 3; }
							else if (bestRun <  131) { sym = (bestRun -  67) / 16 + 277;  extra = (bestRun -  67) % 16;  numExtra = 4; }
							else if (bestRun <  258) { sym = (bestRun - 131) / 32 + 281;  extra = (bestRun - 131) % 32;  numExtra = 5; }
							else if (bestRun == 258) { sym = 285;  extra = 0;  numExtra = 0; }
							else  throw new AssertionError("Unreachable value");
							symbolsAndExtraBits.put((short)(sym << 4 | numExtra));
							litLenHistogram[sym]++;
							symbolsAndExtraBits.put((short)extra);
						}
						{
							int sym, extra, numExtra;
							if      (bestDist <     5) { sym = (bestDist -     1) /    1 +  0;  extra = (bestDist -     1) %    1;  numExtra =  0; }
							else if (bestDist <     9) { sym = (bestDist -     5) /    2 +  4;  extra = (bestDist -     5) %    2;  numExtra =  1; }
							else if (bestDist <    17) { sym = (bestDist -     9) /    4 +  6;  extra = (bestDist -     9) %    4;  numExtra =  2; }
							else if (bestDist <    33) { sym = (bestDist -    17) /    8 +  8;  extra = (bestDist -    17) %    8;  numExtra =  3; }
							else if (bestDist <    65) { sym = (bestDist -    33) /   16 + 10;  extra = (bestDist -    33) %   16;  numExtra =  4; }
							else if (bestDist <   129) { sym = (bestDist -    65) /   32 + 12;  extra = (bestDist -    65) %   32;  numExtra =  5; }
							else if (bestDist <   257) { sym = (bestDist -   129) /   64 + 14;  extra = (bestDist -   129) %   64;  numExtra =  6; }
							else if (bestDist <   513) { sym = (bestDist -   257) /  128 + 16;  extra = (bestDist -   257) %  128;  numExtra =  7; }
							else if (bestDist <  1025) { sym = (bestDist -   513) /  256 + 18;  extra = (bestDist -   513) %  256;  numExtra =  8; }
							else if (bestDist <  2049) { sym = (bestDist -  1025) /  512 + 20;  extra = (bestDist -  1025) %  512;  numExtra =  9; }
							else if (bestDist <  4097) { sym = (bestDist -  2049) / 1024 + 22;  extra = (bestDist -  2049) % 1024;  numExtra = 10; }
							else if (bestDist <  8193) { sym = (bestDist -  4097) / 2048 + 24;  extra = (bestDist -  4097) % 2048;  numExtra = 11; }
							else if (bestDist < 16385) { sym = (bestDist -  8193) / 4096 + 26;  extra = (bestDist -  8193) % 4096;  numExtra = 12; }
							else if (bestDist < 32769) { sym = (bestDist - 16385) / 8192 + 28;  extra = (bestDist - 16385) % 8192;  numExtra = 13; }
							else  throw new AssertionError("Unreachable value");
							symbolsAndExtraBits.put((short)(sym << 4 | numExtra));
							distHistogram[sym]++;
							symbolsAndExtraBits.put((short)extra);
						}
						index += bestRun;
					}
				}
				symbolsAndExtraBits.put((short)(256 << 4));
				litLenHistogram[256]++;
				
				out.writeBits((isFinal ? 1 : 0), 1);  // bfinal
				out.writeBits((!useDynamicHuffmanCodes ? 1 : 2), 2);  // btype
				
				int[] litLenCode;
				int[] distCode;
				if (!useDynamicHuffmanCodes) {
					litLenCode = STATIC_LITERAL_LENGTH_CODE;
					distCode = STATIC_DISTANCE_CODE;
				}
				else {  // Further histogram processing and dynamic code generation
					
					{
						if (dataLen == 0)
							litLenHistogram[0]++;  // Dummy value to fill the Huffman code tree
						int histoEnd = litLenHistogram.length;
						for (; histoEnd > 257 && litLenHistogram[histoEnd - 1] == 0; histoEnd--);
						if (histoEnd < litLenHistogram.length)
							litLenHistogram = Arrays.copyOf(litLenHistogram, histoEnd);
					}
					byte[] litLenCodeLen = calcHuffmanCodeLengths(litLenHistogram, 15);
					
					{
						int numDistCodesUsed = 0;
						for (int x : distHistogram) {
							if (x > 0)
								numDistCodesUsed++;
						}
						if (numDistCodesUsed == 1) {
							for (int i = 0; i < distHistogram.length; i++) {
								if (distHistogram[i] > 0) {
									if (distHistogram.length - i > 1)
										distHistogram[i + 1] = 1;
									else
										distHistogram[i - 1] = 1;
									break;
								}
							}
						}
						int histoEnd = distHistogram.length;
						for (; histoEnd > 1 && distHistogram[histoEnd - 1] == 0; histoEnd--);
						if (histoEnd < distHistogram.length)
							distHistogram = Arrays.copyOf(distHistogram, histoEnd);
					}
					byte[] distCodeLen;
					if (distHistogram.length == 1 && distHistogram[0] == 0)
						distCodeLen = new byte[]{0};
					else
						distCodeLen = calcHuffmanCodeLengths(distHistogram, 15);
					
					var codeLens = new byte[litLenCodeLen.length + distCodeLen.length];
					System.arraycopy(litLenCodeLen, 0, codeLens, 0, litLenCodeLen.length);
					System.arraycopy(distCodeLen, 0, codeLens, litLenCodeLen.length, distCodeLen.length);
					
					List<Integer> codeLengthSymbols = new ArrayList<>();
					List<Integer> extraBits = new ArrayList<>();
					for (int i = 0; i < codeLens.length; ) {  // Greedy algorithm
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
					
					var codeLenHistogram = new int[19];
					for (int sym : codeLengthSymbols)
						codeLenHistogram[sym]++;
					byte[] codeLenCodeLen = calcHuffmanCodeLengths(codeLenHistogram, 7);
					
					var reordered = new int[codeLenCodeLen.length];
					for (int i = 0; i < reordered.length; i++)
						reordered[i] = codeLenCodeLen[CODE_LENGTH_CODE_ORDER[i]];
					int numCodeLenCodeLens = reordered.length;
					for (; numCodeLenCodeLens > 4 && reordered[numCodeLenCodeLens - 1] == 0; numCodeLenCodeLens--);
					
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
					
					litLenCode = codeLengthsToCodes(litLenCodeLen, 15);
					if (distCodeLen.length == 1 && distCodeLen[0] == 0)
						distCode = null;
					else
						distCode = codeLengthsToCodes(distCodeLen, 15);
				}
				
				symbolsAndExtraBits.flip();
				while (symbolsAndExtraBits.hasRemaining()) {
					int litLenPair = symbolsAndExtraBits.get();
					int litLenSym = litLenPair >>> 4;
					assert 0 <= litLenSym && litLenSym <= 285;
					int lenNumExtra = litLenPair & 0xF;
					int litLenCodePair = litLenCode[litLenSym];
					out.writeBits(litLenCodePair >>> 4, litLenCodePair & 0xF);
					if (litLenSym > 256) {
						out.writeBits(symbolsAndExtraBits.get(), lenNumExtra);
						int distPair = symbolsAndExtraBits.get();
						int distSym = distPair >>> 4;
						assert 0 <= distSym && distSym <= 29;
						int distNumExtra = distPair & 0xF;
						int distCodePair = distCode[distSym];
						out.writeBits(distCodePair >>> 4, distCodePair & 0xF);
						out.writeBits(symbolsAndExtraBits.get(), distNumExtra);
					}
				}
			}
		};
	}
	
	
	public static final int ABSOLUTE_MINIMUM_RUN_LENGTH = 3;
	public static final int ABSOLUTE_MAXIMUM_RUN_LENGTH = 258;
	
	public static final int ABSOLUTE_MINIMUM_DISTANCE = 1;
	public static final int ABSOLUTE_MAXIMUM_DISTANCE = 32 * 1024;
	
	
	
	private static byte[] calcHuffmanCodeLengths(int[] symbolHistogram, int maxLen) {
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
			Collections.sort(nodes, (x, y) -> Long.compare(x.frequency(), y.frequency()));
			List<Node> newNodes = new ArrayList<>();
			for (int j = 0; j + 2 <= nodes.size(); j += 2) {
				Node a = nodes.get(j + 0);
				Node b = nodes.get(j + 1);
				newNodes.add(new InternalNode(a.frequency() + b.frequency(), a, b));
			}
			nodes = newNodes;
		}
		
		var nodeHistogram = new byte[symbolHistogram.length];
		for (int i = 0; i < leaves.size() - 1; i++)
			nodes.get(i).countOccurrences(nodeHistogram);
		return nodeHistogram;
	}
	
	
	
	private interface Node {
		
		public long frequency();
		
		public void countOccurrences(byte[] nodeHistogram);
		
	}
	
	
	private record InternalNode(long frequency, Node... children) implements Node {
		
		public void countOccurrences(byte[] nodeHistogram) {
			for (Node node : children)
				node.countOccurrences(nodeHistogram);
		}
		
	}
	
	
	private record Leaf(long frequency, int symbol) implements Node {
		
		public void countOccurrences(byte[] nodeHistogram) {
			nodeHistogram[symbol]++;
		}
		
	}
	
	
	
	private static final int[] CODE_LENGTH_CODE_ORDER =
		{16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};
	
	
	private static int[] codeLengthsToCodes(byte[] codeLengths, int maxCodeLength) {
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
	
	
	private static final int[] STATIC_LITERAL_LENGTH_CODE;
	static {
		var codeLens = new byte[288];
		int i = 0;
		for (; i < 144; i++) codeLens[i] = 8;
		for (; i < 256; i++) codeLens[i] = 9;
		for (; i < 280; i++) codeLens[i] = 7;
		for (; i < 288; i++) codeLens[i] = 8;
		STATIC_LITERAL_LENGTH_CODE = codeLengthsToCodes(codeLens, 9);
	}
	
	private static final int[] STATIC_DISTANCE_CODE;
	static {
		var codeLens = new byte[32];
		Arrays.fill(codeLens, (byte)5);
		STATIC_DISTANCE_CODE = codeLengthsToCodes(codeLens, 5);
	}
	
}
