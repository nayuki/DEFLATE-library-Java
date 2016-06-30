package io.nayuki.deflate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;


public final class InflaterInputStreamTest {
	
	@Test public void testRandomUncompressed() throws IOException {
		for (int i = 0; i < 10000; i++) {
			ByteArrayOutputStream bout0 = new ByteArrayOutputStream();
			ByteArrayOutputStream bout1 = new ByteArrayOutputStream();
			for (int j = 100; j > 0; j--) {
				int len;
				if (rand.nextDouble() < 0.01)
					len = rand.nextInt(10000);
				else
					len = rand.nextInt(30);
				byte[] block = new byte[len];
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
			
			ByteArrayInputStream bin = new ByteArrayInputStream(comp);
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			InflaterInputStream iin = new InflaterInputStream(bin);
			for (int remain = uncomp.length; remain > 0; ) {
				byte[] b = new byte[rand.nextInt(Math.min(remain + 1, 30))];
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
	
	
	private static Random rand = new Random();
	
}
