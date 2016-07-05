/* 
 * DEFLATE library (Java)
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/deflate-library-java
 * https://github.com/nayuki/DEFLATE-library-Java
 */

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.zip.CRC32;
import io.nayuki.deflate.InflaterInputStream;
import io.nayuki.deflate.MarkableFileInputStream;


/**
 * Decompression application for the gzip file format.
 * <p>Usage: java gunzip InputFile OutputFile</p>
 * <p>This decompresses a single gzip file into a single output file. The program also prints
 * some information to standard output, and error messages if the file is invalid/corrupt.</p>
 */
public final class gunzip {
	
	public static void main(String[] args) {
		String msg = submain(args);
		if (msg != null) {
			System.err.println(msg);
			System.exit(1);
		}
	}
	
	
	// Returns null if successful, otherwise returns an error message string.
	private static String submain(String[] args) {
		// Check arguments
		if (args.length != 2)
			return "Usage: java gunzip InputFile.gz OutputFile";
		
		File inFile = new File(args[0]);
		if (!inFile.exists())
			return "Input file does not exist: " + inFile;
		if (inFile.isDirectory())
			return "Input file is a directory: " + inFile;
		File outFile = new File(args[1]);
		
		try (DataInputStream din = new DataInputStream(new MarkableFileInputStream(inFile))) {
			// Read and process fixed-size header
			int flags;
			{
				byte[] b = new byte[10];
				din.readFully(b);
				if (b[0] != 0x1F || b[1] != (byte)0x8B)
					return "Invalid GZIP magic number";
				if (b[2] != 8)
					return "Unsupported compression method: " + (b[2] & 0xFF);
				flags = b[3] & 0xFF;
				
				// Reserved flags
				if ((flags & 0xE0) != 0)
					return "Reserved flags are set";
				
				// Modification time
				int mtime = (b[4] & 0xFF) | (b[5] & 0xFF) << 8 | (b[6] & 0xFF) << 16 | b[7] << 24;
				if (mtime != 0)
					System.err.println("Last modified: " + new Date(mtime * 1000L));
				else
					System.err.println("Last modified: N/A");
				
				// Extra flags
				switch (b[8] & 0xFF) {
					case 2:   System.err.println("Extra flags: Maximum compression");  break;
					case 4:   System.err.println("Extra flags: Fastest compression");  break;
					default:  System.err.println("Extra flags: Unknown (" + (b[8] & 0xFF) + ")");  break;
				}
				
				// Operating system
				String os;
				switch (b[9] & 0xFF) {
					case   0:  os = "FAT";             break;
					case   1:  os = "Amiga";           break;
					case   2:  os = "VMS";             break;
					case   3:  os = "Unix";            break;
					case   4:  os = "VM/CMS";          break;
					case   5:  os = "Atari TOS";       break;
					case   6:  os = "HPFS";            break;
					case   7:  os = "Macintosh";       break;
					case   8:  os = "Z-System";        break;
					case   9:  os = "CP/M";            break;
					case  10:  os = "TOPS-20";         break;
					case  11:  os = "NTFS";            break;
					case  12:  os = "QDOS";            break;
					case  13:  os = "Acorn RISCOS";    break;
					case 255:  os = "Unknown";         break;
					default :  os = "Really unknown";  break;
				}
				System.err.println("Operating system: " + os);
			}
			
			// Handle assorted flags and read more data
			{
				if ((flags & 0x01) != 0)
					System.err.println("Flag: Text");
				if ((flags & 0x04) != 0) {
					System.err.println("Flag: Extra");
					int len = readLittleEndianUint16(din);
					din.readFully(new byte[len]);  // Skip extra data
				}
				if ((flags & 0x08) != 0)
					System.err.println("File name: " + readNullTerminatedString(din));
				if ((flags & 0x02) != 0) {
					byte[] b = new byte[2];
					din.readFully(b);
					System.err.printf("Header CRC-16: %04X%n", (b[0] & 0xFF) | (b[1] & 0xFF) << 8);
				}
				if ((flags & 0x10) != 0)
					System.err.println("Comment: " + readNullTerminatedString(din));
			}
			
			// Start decompressing and writing output file
			long elapsedTime;
			OutputStream fout = new FileOutputStream(outFile);
			try {
				LengthCrc32OutputStream lcout = new LengthCrc32OutputStream(fout);
				InflaterInputStream iin = new InflaterInputStream(din, true);
				byte[] buf = new byte[64 * 1024];
				long startTime = System.nanoTime();
				while (true) {
					int n = iin.read(buf);
					if (n == -1)
						break;
					lcout.write(buf, 0, n);
				}
				elapsedTime = System.nanoTime() - startTime;
				System.err.printf("Input  speed: %.2f MiB/s%n",  inFile.length() / 1048576.0 / elapsedTime * 1.0e9);
				System.err.printf("Output speed: %.2f MiB/s%n", outFile.length() / 1048576.0 / elapsedTime * 1.0e9);
				
				// Process gzip footer
				iin.detach();
				if (lcout.getCrc32() != readLittleEndianInt32(din))
					return "Decompression CRC-32 mismatch";
				if ((int)lcout.getLength() != readLittleEndianInt32(din))
					return "Decompressed size mismatch";
			} finally {
				fout.close();
			}
			
		} catch (IOException e) {
			return "I/O exception: " + e.getMessage();
		}
		return null;
	}
	
	
	
	/*---- Helper methods and class ----*/
	
	private static String readNullTerminatedString(DataInput in) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		while (true) {
			byte b = in.readByte();
			if (b == 0)
				break;
			bout.write(b);
		}
		return new String(bout.toByteArray(), StandardCharsets.UTF_8);
	}
	
	
	private static int readLittleEndianUint16(DataInput in) throws IOException {
		return Integer.reverseBytes(in.readUnsignedShort()) >>> 16;
	}
	
	
	private static int readLittleEndianInt32(DataInput in) throws IOException {
		return Integer.reverseBytes(in.readInt());
	}
	
	
	
	private static final class LengthCrc32OutputStream extends FilterOutputStream {
		
		private long length;  // Total number of bytes written, modulo 2^64
		private CRC32 checksum;
		
		
		public LengthCrc32OutputStream(OutputStream out) {
			super(out);
			length = 0;
			checksum = new CRC32();
		}
		
		
		public void write(int b) throws IOException {
			out.write(b);
			length++;
			checksum.update(b);
		}
		
		
		public void write(byte[] b, int off, int len) throws IOException {
			out.write(b, off, len);
			length += len;
			checksum.update(b, off, len);
		}
		
		
		public long getLength() {
			return length;
		}
		
		
		public int getCrc32() {
			return (int)checksum.getValue();
		}
		
	}
	
}
