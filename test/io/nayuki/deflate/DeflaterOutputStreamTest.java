package io.nayuki.deflate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;
import java.util.zip.Adler32;
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
		// Wrap a zlib container around the compressed data
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		bout.write(0x78);
		bout.write(0xDA);
		bout.write(comp);
		Adler32 chksum = new Adler32();
		chksum.update(uncomp);
		for (int i = 3; i >= 0; i--)
			bout.write((int)chksum.getValue() >>> (i * 8));
		comp = bout.toByteArray();
		
		// Use Java's built-in inflater to decompress the data
		bout = new ByteArrayOutputStream();
		OutputStream iout = new InflaterOutputStream(bout);
		iout.write(comp);
		iout.close();
		Assert.assertArrayEquals(uncomp, bout.toByteArray());
	}
	
	
	private static Random rand = new Random();
	
}
