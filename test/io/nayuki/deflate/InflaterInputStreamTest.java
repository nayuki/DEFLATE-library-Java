/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;


public final class InflaterInputStreamTest {
	
	@Test public void testRandomUncompressed() throws IOException {
		for (int i = 0; i < 10000; i++) {
			var bout0 = new ByteArrayOutputStream();
			var bout1 = new ByteArrayOutputStream();
			for (int j = 100; j > 0; j--) {
				int len;
				if (rand.nextDouble() < 0.01)
					len = rand.nextInt(10000);
				else
					len = rand.nextInt(30);
				var block = new byte[len];
				rand.nextBytes(block);
				bout0.write(block);
				
				bout1.write(j > 1 ? 0x00 : 0x01);
				bout1.write(len >>> 0);
				bout1.write(len >>> 8);
				bout1.write(~len >>> 0);
				bout1.write(~len >>> 8);
				bout1.write(block);
			}
			byte[] uncomp = bout0.toByteArray();
			byte[] comp = bout1.toByteArray();
			
			var bin = new ByteArrayInputStream(comp);
			var bout = new ByteArrayOutputStream();
			var iin = new InflaterInputStream(bin, false);
			for (int remain = uncomp.length; remain > 0; ) {
				var b = new byte[rand.nextInt(Math.min(remain + 1, 30))];
				int n = iin.read(b);
				Assert.assertTrue(n >= 0);
				bout.write(b, 0, n);
				remain -= n;
			}
			Assert.assertEquals(-1, iin.read(new byte[rand.nextInt(10) + 1]));
			Assert.assertEquals(-1, iin.read(new byte[0]));
			Assert.assertArrayEquals(uncomp, bout.toByteArray());
		}
	}
	
	
	@Test(expected=EOFException.class)
	public void testEofStartOfBlock() throws IOException {
		// No blocks
		test("",
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testReservedBlockType() throws IOException {
		// Reserved block type
		test("1 11 00000",
			"");
	}
	
	
	@Test(expected=EOFException.class)
	public void testEofInBlockType() throws IOException {
		// Partial block type
		test("1 0",
			"");
	}
	
	
	@Test
	public void testUncompressedEmpty() throws IOException {
		// Uncompressed block len=0: (empty)
		test("1 00 00000   0000000000000000 1111111111111111",
			"");
	}
	
	
	@Test
	public void testUncompressedThreeBytes() throws IOException {
		// Uncompressed block len=3: 05 14 23
		test("1 00 00000   1100000000000000 0011111111111111   10100000 00101000 11000100",
			"05 14 23");
	}
	
	
	@Test
	public void testUncompressedTwoBlocks() throws IOException {
		// Uncompressed block len=1: 05
		// Uncompressed block len=2: 14 23
		test("0 00 00000   0100000000000000 1011111111111111   10100000 00101000   1 00 00000   1000000000000000 0111111111111111   11000100",
			"05 14 23");
	}
	
	
	@Test(expected=EOFException.class)
	public void testUncompressedEofBeforeLength() throws IOException {
		// Uncompressed block (partial padding) (no length)
		test("1 00 000",
			"");
	}
	
	
	@Test(expected=EOFException.class)
	public void testUncompressedEofInLength() throws IOException {
		// Uncompressed block (partial length)
		test("1 00 00000 0000000000",
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testUncompressedMismatchedLength() throws IOException {
		// Uncompressed block (mismatched len and nlen)
		test("1 00 00000 0010000000010000 1111100100110101",
			"");
	}
	
	
	@Test(expected=EOFException.class)
	public void testUncompressedEofInData() throws IOException {
		// Uncompressed block len=6: 55 EE (End)
		test("1 00 11111 0110000000000000 1001111111111111 10101010 01110111",
			"");
	}
	
	
	@Test(expected=EOFException.class)
	public void testUncompressedBlockNoFinalBlock() throws IOException {
		// Uncompressed block len=0: (empty)
		// No final block
		test("0 00 00000   0000000000000000 1111111111111111",
			"");
	}
	
	
	@Test
	public void testUncompressedBlockNoDiscardBits() throws IOException {
		// Fixed Huffman block: 90 A1 FF End
		// Uncompressed block len=2: AB CD
		test("0 10 110010000 110100001 111111111 0000000  1 00 0100000000000000 1011111111111111 11010101 10110011",
			"90 A1 FF AB CD");
	}
	
	
	@Test
	public void testFixedHuffmanEmpty() throws IOException {
		// Fixed Huffman block: End
		test("1 10 0000000",
			"");
	}
	
	
	@Test
	public void testFixedHuffmanLiterals() throws IOException {
		// Fixed Huffman block: 00 80 8F 90 C0 FF End
		test("1 10 00110000 10110000 10111111 110010000 111000000 111111111 0000000",
			"00 80 8F 90 C0 FF");
	}
	
	
	@Test
	public void testFixedHuffmanNonOverlappingRun() throws IOException {
		// Fixed Huffman block: 00 01 02 (3,3) End
		test("1 10 00110000 00110001 00110010 0000001 00010 0000000",
			"00 01 02 00 01 02");
	}
	
	
	@Test
	public void testFixedHuffmanOverlappingRun0() throws IOException {
		// Fixed Huffman block: 01 (1,4) End
		test("1 10 00110001 0000010 00000 0000000",
			"01 01 01 01 01");
	}
	
	
	@Test
	public void testFixedHuffmanOverlappingRun1() throws IOException {
		// Fixed Huffman block: 8E 8F (2,5) End
		test("1 10 10111110 10111111 0000011 00001 0000000",
			"8E 8F 8E 8F 8E 8F 8E");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testFixedHuffmanInvalidLengthCode286() throws IOException {
		// Fixed Huffman block: #286
		test("1 10 11000110",
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testFixedHuffmanInvalidLengthCode287() throws IOException {
		// Fixed Huffman block: #287
		test("1 10 11000111",
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testFixedHuffmanInvalidDistanceCode30() throws IOException {
		// Fixed Huffman block: 00 #257 #30
		test("1 10 00110000 0000001 11110",
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testFixedHuffmanInvalidDistanceCode31() throws IOException {
		// Fixed Huffman block: 00 #257 #31
		test("1 10 00110000 0000001 11111",
			"");
	}
	
	
	@Test(expected=EOFException.class)
	public void testFixedHuffmanEofInHuffmanSymbol() throws IOException {
		// Fixed Huffman block: (partial symbol)
		test("1 10 00000",
			"");
	}
	
	
	@Test(expected=EOFException.class)
	public void testFixedHuffmanEofInRunExtensionBits() throws IOException {
		// Fixed Huffman block: 00 #269+1(partial)
		test("1 10 00110000 0001101 1",
			"");
	}
	
	
	@Test(expected=EOFException.class)
	public void testFixedHuffmanEofInDistanceExtensionBits() throws IOException {
		// Fixed Huffman block: 00 #285 #0 #257 #8+00(partial)
		test("1 10 00110000 11000101 00000 0000001 01000 00",
			"");
	}
	
	
	@Test
	public void testDynamicHuffmanEmpty() throws IOException {
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
	
	
	@Test
	public void testDynamicHuffmanEmptyNoDistanceCode() throws IOException {
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
	
	
	@Test(expected=DataFormatException.class)
	public void testDynamicHuffmanCodeLengthRepeatAtStart() throws IOException {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=18
		//   codeLenCodeLen = 0:0, 1:1, 2:0, ..., 15:0, 16:1, 17:0, 18:0
		//   Literal/length/distance code lengths: #16+00
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0111";
		String codeLenCodeLens = "100 000 000 000 000 000 000 000 000 000 000 000 000 000 000 000 000 100";
		String codeLens = "1";
		test(blockHeader + codeCounts + codeLenCodeLens + codeLens,
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testDynamicHuffmanTooManyCodeLengthItems() throws IOException {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=18
		//   codeLenCodeLen = 0:0, 1:1, 2:0, ..., 15:0, 16:0, 17:0, 18:1
		//   Literal/length/distance code lengths: 1 1 #18+1111111 #18+1101100
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0111";
		String codeLenCodeLens = "000 000 100 000 000 000 000 000 000 000 000 000 000 000 000 000 000 100";
		String codeLens = "0 0 11111111 10011011";
		test(blockHeader + codeCounts + codeLenCodeLens + codeLens,
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testDynamicHuffmanOverfullCode0() throws IOException {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:1, 1:1, 2:1, 3:0
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "100 100 100 000";
		String padding = "0000000000000000000";
		test(blockHeader + codeCounts + codeLenCodeLens + padding,
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testDynamicHuffmanOverfullCode1() throws IOException {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:1, 1:1, 2:1, 3:1
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "100 100 100 100";
		String padding = "0000000000000000000";
		test(blockHeader + codeCounts + codeLenCodeLens + padding,
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testDynamicHuffmanUnpairedCode() throws IOException {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:1, 1:2, 2:3, 3:0
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "100 010 110 000";
		String padding = "0000000000000000000";
		test(blockHeader + codeCounts + codeLenCodeLens + padding,
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testDynamicHuffmanEmptyCode() throws IOException {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:0, 1:0, 2:0, 3:0
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "000 000 000 000";
		String padding = "0000000000000000000";
		test(blockHeader + codeCounts + codeLenCodeLens + padding,
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testDynamicHuffmanUnderfullCode0() throws IOException {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:0, 1:0, 2:1, 3:0
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "000 000 100 000";
		String padding = "0000000000000000000";
		test(blockHeader + codeCounts + codeLenCodeLens + padding,
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testDynamicHuffmanUnderfullCode1() throws IOException {
		// Dynamic Huffman block:
		//   numLitLen=257, numDist=1, numCodeLen=4
		//   codeLenCodeLen = 0:2, 1:1, 2:0, 3:0
		String blockHeader = "1 01";
		String codeCounts = "00000 00000 0000";
		String codeLenCodeLens = "010 100 000 000";
		String padding = "0000000000000000000";
		test(blockHeader + codeCounts + codeLenCodeLens + padding,
			"");
	}
	
	
	@Test(expected=DataFormatException.class)
	public void testDynamicHuffmanUseOfNullDistanceCode() throws IOException {
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
		test(blockHeader + codeCounts + codeLenCodeLens + codeLens + data + padding,
			"");
	}
	
	
	
	/* Utility method */
	
	// 'input' is a string of 0's and 1's (with optional spaces) representing the input bit sequence.
	// 'refOutput' is a string of pairs of hexadecimal digits (with optional spaces) representing
	// the expected decompressed output byte sequence.
	private static void test(String inputBits, String refOutputHex) throws IOException {
		// Remove spaces and convert hexadecimal to bytes
		refOutputHex = refOutputHex.replace(" ", "");
		if (refOutputHex.length() % 2 != 0)
			throw new IllegalArgumentException();
		var refOut = new byte[refOutputHex.length() / 2];
		for (int i = 0; i < refOut.length; i++)
			refOut[i] = (byte)Integer.parseInt(refOutputHex.substring(i * 2, (i + 1) * 2), 16);
		
		// Preprocess the bit string
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
		
		// Perform decompression with block reads and check output
		var bout = new ByteArrayOutputStream();
		var iin = new InflaterInputStream(new StringInputStream(inputBits), false);
		var buf = new byte[rand.nextInt(10) + 1];
		while (true) {
			int n = iin.read(buf);
			if (n == -1)
				break;
			bout.write(buf, 0, n);
		}
		Assert.assertArrayEquals(refOut, bout.toByteArray());
		
		// Perform decompression with single-byte reads and check output
		bout = new ByteArrayOutputStream();
		iin = new InflaterInputStream(new StringInputStream(inputBits), false);
		while (true) {
			int b = iin.read();
			if (b == -1)
				break;
			bout.write(b);
		}
		Assert.assertArrayEquals(refOut, bout.toByteArray());
	}
	
	
	private static Random rand = new Random();
	
}
