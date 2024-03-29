/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;
import io.nayuki.deflate.DataFormatException.Reason;


public final class InflaterInputStreamTest {
	
	/*---- Block header ----*/
	
	@Test public void testHeaderEndBeforeFinal() {
		testFail("",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testHeaderEndBeforeType() {
		// Fixed Huffman block: 90 91 92 93 94 End
		testFail("0 10 110010000 110010001 110010010 110010011 110010100 0000000"
			+ "1",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testHeaderEndInType() {
		// Fixed Huffman block: 95 96 97 98 End
		testFail("0 10 110010101 110010110 110010111 110011000 0000000"
			+ "1 0",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	
	/*---- Block type 0b00 ----*/
	
	@Test public void testUncompressedEmpty() {
		// Uncompressed block len=0: (empty)
		test("1 00 00000   0000000000000000 1111111111111111",
			"");
	}
	
	
	@Test public void testUncompressedThreeBytes() {
		// Uncompressed block len=3: 05 14 23
		test("1 00 00000   1100000000000000 0011111111111111   10100000 00101000 11000100",
			"05 14 23");
	}
	
	
	@Test public void testUncompressedTwoBlocks() {
		// Uncompressed block len=2: 05 14
		// Uncompressed block len=1: 23
		test("0 00 00000   0100000000000000 1011111111111111   10100000 00101000"
			+ "1 00 00000   1000000000000000 0111111111111111   11000100",
			"05 14 23");
	}
	
	
	@Test public void testUncompressedEndBeforeLength() {
		// Uncompressed block (partial padding) (no length)
		testFail("1 00 000",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testUncompressedEndInLength() {
		// Uncompressed block (partial length)
		testFail("1 00 00000 0000000000",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testUncompressedEndInNegatedLength() {
		// Uncompressed block (len) (partial nlen)
		testFail("1 00 00000 0000000000000000 11111111",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testUncompressedLengthNegatedMismatch() {
		// Uncompressed block (mismatched len and nlen)
		testFail("1 00 00000 0010000000010000 1111100100110101",
			Reason.UNCOMPRESSED_BLOCK_LENGTH_MISMATCH);
	}
	
	
	@Test public void testUncompressedEndBeforeData() {
		// Uncompressed block len=6: (End)
		testFail("1 00 11111 0110000000000000 1001111111111111",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testUncompressedEndInData() {
		// Uncompressed block len=6: 55 EE (End)
		testFail("1 00 11111 0110000000000000 1001111111111111 10101010 01110111",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testUncompressedEndBeforeFinalBlock() {
		// Uncompressed block len=0: (empty)
		// No final block
		testFail("0 00 00000   0000000000000000 1111111111111111",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testUncompressedAlreadyByteAligned() {
		// Fixed Huffman block: 90 A1 FF End
		// Uncompressed block len=2: AB CD
		test("0 10 110010000 110100001 111111111 0000000  "
			+ "1 00 0100000000000000 1011111111111111 11010101 10110011",
			"90 A1 FF AB CD");
	}
	
	
	@Test public void testUncompressedRandom() {
		final int TRIALS = 100;
		for (int i = 0; i < TRIALS; i++) {
			int numBlocks = rand.nextInt(30) + 1;
			var inBits = new StringBuilder();
			var outBytes = new StringBuilder();
			for (int j = 0; j < numBlocks; j++) {
				inBits.append(j + 1 < numBlocks ? "0" : "1");  // bfinal
				inBits.append("00");  // btype
				for (int k = 0; k < 5; k++)  // Padding
					inBits.append(rand.nextInt(2));
				
				// A quasi log-uniform distribution
				int len = rand.nextInt(17);
				if (len > 0) {
					len = 1 << (len - 1);
					len |= rand.nextInt(len);
				}
				int temp = len | ((~len) << 16);
				for (int k = 0; k < 32; k++)
					inBits.append((temp >>> k) & 1);
				
				var data = new byte[len];
				rand.nextBytes(data);
				for (byte b : data) {
					outBytes.append(String.format("%02x", b));
					for (int k = 0; k < 8; k++, b >>>= 1)
						inBits.append(b & 1);
				}
			}
			test(inBits.toString(), outBytes.toString());
		}
	}
	
	
	@Test public void testUncompressedRandomAndShortFixedHuffman() {
		final int TRIALS = 100;
		for (int i = 0; i < TRIALS; i++) {
			int numBlocks = rand.nextInt(30) + 1;
			var inBits = new StringBuilder();
			var outBytes = new StringBuilder();
			for (int j = 0; j < numBlocks; j++) {
				inBits.append(j + 1 < numBlocks ? "0" : "1");  // bfinal
				if (rand.nextDouble() < 0.5) {
					inBits.append("00");  // btype
					while (inBits.length() % 8 != 0)  // Padding
						inBits.append(rand.nextInt(2));
					
					// A quasi log-uniform distribution
					int len = rand.nextInt(17);
					if (len > 0) {
						len = 1 << (len - 1);
						len |= rand.nextInt(len);
					}
					int temp = len | ((~len) << 16);
					for (int k = 0; k < 32; k++)
						inBits.append((temp >>> k) & 1);
					
					var data = new byte[len];
					rand.nextBytes(data);
					for (byte b : data) {
						outBytes.append(String.format("%02x", b));
						for (int k = 0; k < 8; k++, b >>>= 1)
							inBits.append(b & 1);
					}
				} else {
					inBits.append("10");  // btype
					inBits.append("111111111");  // Symbol #255 (0xFF)
					outBytes.append("FF");
					inBits.append("0000000");  // End of block
					// Including bfinal, this writes a total of 19 bits, which is 3
					// modulo 8. By writing many consecutive blocks of this type, the
					// starting position of the next block can be any number mod 8.
				}
			}
			test(inBits.toString(), outBytes.toString());
		}
	}
	
	
	
	/*---- Block type 0b01 ----*/
	
	@Test public void testFixedHuffmanEmpty() {
		// Fixed Huffman block: End
		test("1 10 0000000",
			"");
	}
	
	
	@Test public void testFixedHuffmanLiterals() {
		// Fixed Huffman block: 00 80 8F 90 C0 FF End
		test("1 10 00110000 10110000 10111111 110010000 111000000 111111111 0000000",
			"00 80 8F 90 C0 FF");
	}
	
	
	@Test public void testFixedHuffmanNonOverlappingRun() {
		// Fixed Huffman block: 00 01 02 (3,3) End
		test("1 10 00110000 00110001 00110010 0000001 00010 0000000",
			"00 01 02 00 01 02");
	}
	
	
	@Test public void testFixedHuffmanOverlappingRun1() {
		// Fixed Huffman block: 01 (1,4) End
		test("1 10 00110001 0000010 00000 0000000",
			"01 01 01 01 01");
	}
	
	
	@Test
	public void testFixedHuffmanOverlappingRun2() {
		// Fixed Huffman block: 8E 8F (2,5) End
		test("1 10 10111110 10111111 0000011 00001 0000000",
			"8E 8F 8E 8F 8E 8F 8E");
	}
	
	
	@Test public void testFixedHuffmanInvalidLengthCode286() {
		// Fixed Huffman block: #286
		testFail("1 10 11000110",
			Reason.RESERVED_LENGTH_SYMBOL);
	}
	
	
	@Test public void testFixedHuffmanInvalidLengthCode287() {
		// Fixed Huffman block: #287
		testFail("1 10 11000111",
			Reason.RESERVED_LENGTH_SYMBOL);
	}
	
	
	@Test public void testFixedHuffmanInvalidDistanceCode30() {
		// Fixed Huffman block: 00 #257 #30
		testFail("1 10 00110000 0000001 11110",
			Reason.RESERVED_DISTANCE_SYMBOL);
	}
	
	
	@Test public void testFixedHuffmanInvalidDistanceCode31() {
		// Fixed Huffman block: 00 #257 #31
		testFail("1 10 00110000 0000001 11111",
			Reason.RESERVED_DISTANCE_SYMBOL);
	}
	
	
	@Test public void testFixedHuffmanEndInSymbol() {
		// Fixed Huffman block: (partial symbol)
		testFail("1 10 00000",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testFixedHuffmanEndBeforeSymbol() {
		// Fixed Huffman block: 93 91 94 90 92
		testFail("1 10 110010011 110010001 110010100 110010000 110010010",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testFixedHuffmanEofInRunExtensionBits() {
		// Fixed Huffman block: 00 #269+1(partial)
		testFail("1 10 00110000 0001101 1",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testFixedHuffmanEofInDistanceExtensionBits() {
		// Fixed Huffman block: 00 #285 #0 #257 #8+00(partial)
		testFail("1 10 00110000 11000101 00000 0000001 01000 00",
			Reason.UNEXPECTED_END_OF_STREAM);
	}
	
	
	@Test public void testFixedHuffmanLiteralsRandom() {
		final int TRIALS = 100;
		for (int i = 0; i < TRIALS; i++) {
			int numBlocks = rand.nextInt(100) + 1;
			var inBits = new StringBuilder();
			var outBytes = new StringBuilder();
			for (int j = 0; j < numBlocks; j++) {
				inBits.append(j + 1 < numBlocks ? "0" : "1");  // bfinal
				inBits.append("10");  // btype
				
				// A quasi log-uniform distribution
				int len = rand.nextInt(16);
				if (len > 0) {
					len = 1 << (len - 1);
					len |= rand.nextInt(len);
				}
				
				for (int k = 0; k < len; k++) {
					int b = rand.nextInt(256);
					if (b < 144) {
						for (int l = 7; l >= 0; l--)
							inBits.append(((b - 0 + 48) >>> l) & 1);
					} else {
						for (int l = 8; l >= 0; l--)
							inBits.append(((b - 144 + 400) >>> l) & 1);
					}
					outBytes.append(String.format("%02x", b));
				}
				inBits.append("0000000");
			}
			test(inBits.toString(), outBytes.toString());
		}
	}
	
	
	
	/*---- Block type 0b10 ----*/
	
	@Test public void testDynamicHuffmanEmpty() {
		// Dynamic Huffman block:
		//   numCodeLen=19
		//     codeLenCodeLen = 0:0, 1:1, 2:0, ..., 15:0, 16:0, 17:0, 18:1
		//   numLitLen=257, numDist=2
		//     litLenCodeLen = 0:1, 1:0, ..., 255:0, 256:1
		//     distCodeLen = 0:1, 1:1
		//   Data: End
		String blockHeader = "1 01";
		String codeCounts = "00000 10000 1111";
		String codeLenCodeLens = "000 000 100 000 000 000 000 000 000 000 000 000 000 000 000 000 000 100 000";
		String codeLens = "0 11111111 10101011 0 0 0";
		String data = "1";
		test(blockHeader + codeCounts + codeLenCodeLens + codeLens + data,
			"");
	}
	
	
	@Test public void testDynamicHuffmanEmptyNoDistanceCode() {
		// Dynamic Huffman block:
		//   numCodeLen=18
		//     codeLenCodeLen = 0:2, 1:2, 2:0, ..., 15:0, 16:0, 17:0, 18:1
		//   numLitLen=257, numDist=1
		//     litLenCodeLen = 0:0, ..., 254:0, 255:1, 256:1
		//     distCodeLen = 0:0
		//   Data: End
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0111";
		String codeLenCodeLens = "000 000 100 010 000 000 000 000 000 000 000 000 000 000 000 000 000 010";
		String codeLens = "01111111 00101011 11 11 10";
		String data = "1";
		test(blockHeader + codeCounts + codeLenCodeLens + codeLens + data,
			"");
	}
	
	
	@Test public void testDynamicHuffmanCodeLengthRepeatAtStart() {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=18
		//   codeLenCodeLen = 0:0, 1:1, 2:0, ..., 15:0, 16:1, 17:0, 18:0
		//   Literal/length/distance code lengths: #16+00
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0111";
		String codeLenCodeLens = "100 000 000 000 000 000 000 000 000 000 000 000 000 000 000 000 000 100";
		String codeLens = "1";
		testFail(blockHeader + codeCounts + codeLenCodeLens + codeLens,
			Reason.NO_PREVIOUS_CODE_LENGTH_TO_COPY);
	}
	
	
	@Test public void testDynamicHuffmanTooManyCodeLengthItems() {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=18
		//   codeLenCodeLen = 0:0, 1:1, 2:0, ..., 15:0, 16:0, 17:0, 18:1
		//   Literal/length/distance code lengths: 1 1 #18+1111111 #18+1101100
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0111";
		String codeLenCodeLens = "000 000 100 000 000 000 000 000 000 000 000 000 000 000 000 000 000 100";
		String codeLens = "0 0 11111111 10011011";
		testFail(blockHeader + codeCounts + codeLenCodeLens + codeLens,
			Reason.CODE_LENGTH_CODE_OVER_FULL);
	}
	
	
	@Test public void testDynamicHuffmanOverfullCode0() {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:1, 1:1, 2:1, 3:0
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "100 100 100 000";
		String padding = "0000000000000000000";
		testFail(blockHeader + codeCounts + codeLenCodeLens + padding,
			Reason.HUFFMAN_CODE_OVER_FULL);
	}
	
	
	@Test public void testDynamicHuffmanOverfullCode1() {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:1, 1:1, 2:1, 3:1
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "100 100 100 100";
		String padding = "0000000000000000000";
		testFail(blockHeader + codeCounts + codeLenCodeLens + padding,
			Reason.HUFFMAN_CODE_OVER_FULL);
	}
	
	
	@Test public void testDynamicHuffmanUnpairedCode() {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:1, 1:2, 2:3, 3:0
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "100 010 110 000";
		String padding = "0000000000000000000";
		testFail(blockHeader + codeCounts + codeLenCodeLens + padding,
			Reason.HUFFMAN_CODE_UNDER_FULL);
	}
	
	
	@Test public void testDynamicHuffmanEmptyCode() {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:0, 1:0, 2:0, 3:0
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "000 000 000 000";
		String padding = "0000000000000000000";
		testFail(blockHeader + codeCounts + codeLenCodeLens + padding,
			Reason.HUFFMAN_CODE_UNDER_FULL);
	}
	
	
	@Test public void testDynamicHuffmanUnderfullCode0() {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:0, 1:0, 2:1, 3:0
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "000 000 100 000";
		String padding = "0000000000000000000";
		testFail(blockHeader + codeCounts + codeLenCodeLens + padding,
			Reason.HUFFMAN_CODE_UNDER_FULL);
	}
	
	
	@Test public void testDynamicHuffmanUnderfullCode1() {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:2, 1:1, 2:0, 3:0
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "010 100 000 000";
		String padding = "0000000000000000000";
		testFail(blockHeader + codeCounts + codeLenCodeLens + padding,
			Reason.HUFFMAN_CODE_UNDER_FULL);
	}
	
	
	@Test public void testDynamicHuffmanUseOfNullDistanceCode() {
		// Dynamic Huffman block:
		//   numLitLen=258, numDist=1, numCodeLen=18
		//   codeLenCodeLen = 0:2, 1:2, 2:2, ..., 15:0, 16:0, 17:0, 18:2
		//   Literal/length/distance code lengths: 2 #18+1111111 #18+1101100 1 2 0
		//   Data: 00 #257
		String blockHeader = "1 01";
		String codeCounts = "10000 00000 0111";
		String codeLenCodeLens = "000 000 010 010 000 000 000 000 000 000 000 000 000 000 000 010 000 010";
		String codeLens = "10 111111111 110101011 01 10 00";
		String data = "10 11";
		String padding = "0000000000000000";
		testFail(blockHeader + codeCounts + codeLenCodeLens + codeLens + data + padding,
			Reason.LENGTH_ENCOUNTERED_WITH_EMPTY_DISTANCE_CODE);
	}
	
	
	
	/*---- Block type 0b11 ----*/
	
	@Test public void testReservedBlockType() {
		// Reserved block type
		testFail("1 11 00000",
			Reason.RESERVED_BLOCK_TYPE);
	}
	
	
	
	/*---- Utilities ----*/
	
	// `inputBits` has 0s and 1s, and optional spaces; its length need not be
	// a multiple of 8. `refOutputHex` has pairs of hexadecimal digits (with
	// optional spaces) representing the expected decompressed output byte sequence.
	private static void test(String inputBits, String refOutputHex) {
		// Process the input bit string
		Objects.requireNonNull(inputBits);
		inputBits = inputBits.replace(" ", "");
		int padMode = rand.nextInt(3);
		while (inputBits.length() % 8 != 0) {
			inputBits += switch (padMode) {
				case 0 -> 0;
				case 1 -> 1;
				case 2 -> rand.nextInt(2);
				default -> throw new AssertionError("Unreachable value");
			};
		}
		
		// Convert the reference output hex string
		Objects.requireNonNull(refOutputHex);
		refOutputHex = refOutputHex.replace(" ", "");
		if (refOutputHex.length() % 2 != 0)
			throw new IllegalArgumentException();
		var refOut = new byte[refOutputHex.length() / 2];
		for (int i = 0; i < refOut.length; i++)
			refOut[i] = (byte)Integer.parseInt(refOutputHex.substring(i * 2, (i + 1) * 2), 16);
		
		// Perform decompression with single-byte reads and check output
		var bout = new ByteArrayOutputStream();
		var sin = new StringInputStream(inputBits);
		try {
			@SuppressWarnings("resource")
			var iin = new InflaterInputStream(sin, true);
			while (true) {
				int b = iin.read();
				if (b == -1)
					break;
				bout.write(b);
			}
		} catch (IOException e) {
			throw new AssertionError("Unexpected exception", e);
		}
		if (sin.read() != -1)
			throw new IllegalArgumentException();
		Assert.assertArrayEquals(refOut, bout.toByteArray());
		
		// Perform decompression with block reads and check output
		bout.reset();
		sin = new StringInputStream(inputBits);
		try {
			@SuppressWarnings("resource")
			var iin = new InflaterInputStream(sin, true);
			while (true) {
				var buf = new byte[rand.nextInt(100) + 1];
				int off = rand.nextInt(buf.length + 1);
				int len = rand.nextInt(buf.length - off + 1);
				int n = iin.read(buf, off, len);
				if (!(-1 <= n && n <= len))
					throw new IllegalArgumentException();
				if (n == -1)
					break;
				if (n == 0 && len != 0)
					throw new IllegalArgumentException();
				bout.write(buf, off, n);
			}
		} catch (IOException e) {
			throw new AssertionError("Unexpected exception", e);
		}
		Assert.assertArrayEquals(refOut, bout.toByteArray());
	}
	
	
	private static void testFail(String inputBits, Reason reason) {
		try {
			test(inputBits, "");
		} catch (DataFormatException e) {
			Assert.assertEquals(reason, e.getReason());
		}
	}
	
	
	private static Random rand = new Random();
	
}
