/* 
 * DEFLATE library (Java)
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/deflate-library-java
 * https://github.com/nayuki/DEFLATE-library-Java
 */

package io.nayuki.deflate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;
import org.junit.Assert;
import org.junit.Test;


public class DeflaterOutputStreamTest {
	
	@Test public void testEmpty() throws IOException {
		byte[] data = new byte[0];
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		DeflaterOutputStream dout = new DeflaterOutputStream(bout);
		dout.close();
		checkInflate(data, bout.toByteArray());
	}
	
	
	@Test public void testShortSingleWriteRandomly() throws IOException {
		for (int i = 0; i < 1000; i++) {
			byte[] data = new byte[rand.nextInt(100)];
			rand.nextBytes(data);
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			DeflaterOutputStream dout = new DeflaterOutputStream(bout);
			dout.write(data);
			dout.close();
			checkInflate(data, bout.toByteArray());
		}
	}
	
	
	@Test public void testShortMultiWriteRandomly() throws IOException {
		for (int i = 0; i < 1000; i++) {
			byte[] data = new byte[rand.nextInt(1000)];
			rand.nextBytes(data);
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			DeflaterOutputStream dout = new DeflaterOutputStream(bout);
			for (int off = 0; off < data.length; ) {
				if (rand.nextDouble() < 0.1) {
					dout.write(data[off]);
					off++;
				} else {
					int n = rand.nextInt(Math.min(100, data.length - off)) + 1;
					dout.write(data, off, n);
					off += n;
				}
			}
			dout.close();
			checkInflate(data, bout.toByteArray());
		}
	}
	
	
	@Test public void testLongRandomly() throws IOException {
		for (int i = 0; i < 1000; i++) {
			byte[] data = new byte[rand.nextInt(1000000)];
			rand.nextBytes(data);
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			DeflaterOutputStream dout = new DeflaterOutputStream(bout);
			for (int off = 0; off < data.length; ) {
				if (rand.nextDouble() < 0.9) {
					dout.write(data[off]);
					off++;
				} else {
					int n = rand.nextInt(Math.min(300000, data.length - off)) + 1;
					dout.write(data, off, n);
					off += n;
				}
			}
			dout.close();
			checkInflate(data, bout.toByteArray());
		}
	}
	
	
	
	private static void checkInflate(byte[] uncomp, byte[] comp) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		OutputStream iout = new InflaterOutputStream(bout, new Inflater(true));
		iout.write(comp);
		iout.write(0);  // Extra dummy data as per the API spec
		iout.close();
		Assert.assertArrayEquals(uncomp, bout.toByteArray());
	}
	
	
	private static Random rand = new Random();
	
}
