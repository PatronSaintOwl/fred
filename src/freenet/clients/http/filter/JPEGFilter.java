/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashMap;

import freenet.l10n.L10n;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.io.Closer;
import freenet.support.io.CountedInputStream;

/**
 * Content filter for JPEG's.
 * Just check the header.
 * 
 * http://www.obrador.com/essentialjpeg/headerinfo.htm
 * Also the JFIF spec.
 * Also http://cs.haifa.ac.il/~nimrod/Compression/JPEG/J6sntx2005.pdf
 * http://svn.xiph.org/experimental/giles/jpegdump.c
 * http://it.jeita.or.jp/document/publica/standard/exif/english/jeida49e.htm
 *
 * L10n: Only the overall explanation message and the "too short" messages are localised.
 * It's probably not worth doing the others, they're way too detailed.
 */
public class JPEGFilter implements ContentDataFilter {

	private final boolean deleteComments;
	private final boolean deleteExif;

	private static final int MARKER_EOI = 0xD9; // End of image
	//private static final int MARKER_SOI = 0xD8; // Start of image
	private static final int MARKER_RST0 = 0xD0; // First reset marker
	private static final int MARKER_RST7 = 0xD7; // Last reset marker
	
	JPEGFilter(boolean deleteComments, boolean deleteExif) {
		this.deleteComments = deleteComments;
		this.deleteExif = deleteExif;
	}
	
	static final byte[] soi = new byte[] {
		(byte)0xFF, (byte)0xD8 // Start of Image
	};
	static final byte[] identifier = new byte[] {
		(byte)'J', (byte)'F', (byte)'I', (byte)'F', 0
	};
	static final byte[] extensionIdentifier = new byte[] {
		(byte)'J', (byte)'F', (byte)'X', (byte)'X', 0
	};
	
	public Bucket readFilter(Bucket data, BucketFactory bf, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb) throws DataFilterException, IOException {
		Bucket output = readFilter(data, bf, charset, otherParams, cb, deleteComments, deleteExif, null);
		if(output != null)
			return output;
		if(Logger.shouldLog(Logger.MINOR, this))
			Logger.minor(this, "Need to modify JPEG...");
		Bucket filtered = bf.makeBucket(-1);
		OutputStream os = new BufferedOutputStream(filtered.getOutputStream());
		try {
			readFilter(data, bf, charset, otherParams, cb, deleteComments, deleteExif, os);
			os.flush();
			os.close();
			os = null;
		} finally {
			Closer.close(os);
		}
		return filtered;
	}
	
	public Bucket readFilter(Bucket data, BucketFactory bf, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb, boolean deleteComments, boolean deleteExif, OutputStream output)
	        throws DataFilterException, IOException {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		long length = data.size();
		if(length < 6) {
			throwError(l10n("tooShortTitle"), l10n("tooShort"));
		}
		InputStream is = data.getInputStream();
		BufferedInputStream bis = new BufferedInputStream(is);
		CountedInputStream cis = new CountedInputStream(bis);
		DataInputStream dis = new DataInputStream(cis);
		try {
			assertHeader(dis, soi);
			if(output != null) output.write(soi);
			
			ByteArrayOutputStream baos = null;
			DataOutputStream dos = null;
			if(output != null) {
				baos = new ByteArrayOutputStream();
				dos = new DataOutputStream(baos);
			}
			
			// Check the chunks.
			
			boolean finished = false;
			int forceMarkerType = -1;
			while(dos == null || !finished) {
				if(baos != null)
					baos.reset();
				int markerType;
				if(forceMarkerType != -1) {
					markerType = forceMarkerType;
					forceMarkerType = -1;
				} else {
					int markerStart = dis.read();
					if(markerStart == -1) {
						// No more chunks to scan.
						break;
					} else if(finished) {
						if(logMINOR)
							Logger.minor(this, "More data after EOI, copying to truncate");
						return null;
					}
					if(markerStart != 0xFF) {
						throwError("Invalid marker", "The file includes an invalid marker start "+Integer.toHexString(markerStart)+" and cannot be parsed further.");
					}
					if(baos != null) baos.write(0xFF);
					markerType = dis.readUnsignedByte();
					if(baos != null) baos.write(markerType);
				}
				if(logMINOR)
					Logger.minor(this, "Marker type: "+Integer.toHexString(markerType));
				long countAtStart = cis.count(); // After marker but before type
				int blockLength;
				if(markerType == MARKER_EOI || markerType >= MARKER_RST0 && markerType <= MARKER_RST7)
					blockLength = 0;
				else {
					blockLength = dis.readUnsignedShort();
					if(dos != null) dos.writeShort(blockLength);
				}
				if(markerType == 0xDA) {
					// Start of scan marker
					
					// Copy marker
					if(blockLength < 2)
						throwError("Invalid frame length", "The file includes an invalid frame (length "+blockLength+").");
					if(dos != null) {
						byte[] buf = new byte[blockLength - 2];
						dis.readFully(buf);
						dos.write(buf);
					} else
						skipBytes(dis, blockLength - 2);
					Logger.minor(this, "Copied start-of-frame marker length "+(blockLength-2));
					
					if(baos != null)
						baos.writeTo(output); // will continue; at end
					
					// Now copy the scan itself
					
					int prevChar = -1;
					while(true) {
						int x = dis.read();
						if(prevChar != -1 && output != null) {
							output.write(prevChar);
						}
						if(x == -1) {
							// Termination inside a scan; valid I suppose
							break;
						}
						if(prevChar == 0xFF && x != 0 &&
								!(x >= MARKER_RST0 && x <= MARKER_RST7)) { // reset markers can occur in the scan
							
							forceMarkerType = x;
							if(logMINOR)
								Logger.minor(this, "Moved scan at "+cis.count()+", found a marker type "+Integer.toHexString(x));
							if(output != null) output.write(x);
							break; // End of scan, new marker
						}
						prevChar = x;
					}
					
					continue; // Avoid writing the header twice
					
				} else if(markerType == 0xE0) { // APP0
					if(logMINOR) Logger.minor(this, "APP0");
					String type = readNullTerminatedAsciiString(dis);
					if(baos != null) writeNullTerminatedString(baos, type);
					if(logMINOR) Logger.minor(this, "Type: "+type+" length "+type.length());
					if(type.equals("JFIF")) {
						Logger.minor(this, "JFIF Header");
						// File header
						int majorVersion = dis.readUnsignedByte();
						if(majorVersion != 1)
							throwError("Invalid header", "Unrecognized major version "+majorVersion+".");
						if(dos != null) dos.write(majorVersion);
						int minorVersion = dis.readUnsignedByte();
						if(minorVersion > 2)
							throwError("Invalid header", "Unrecognized version 1."+minorVersion+".");
						if(dos != null) dos.write(minorVersion);
						int units = dis.readUnsignedByte();
						if(units > 2)
							throwError("Invalid header", "Unrecognized units type "+units+".");
						if(dos != null) dos.write(units);
						if(dos != null) {
							dos.writeShort(dis.readShort()); // Copy Xdensity
							dos.writeShort(dis.readShort()); // Copy Ydensity
						} else {
							dis.readShort(); // Ignore Xdensity
							dis.readShort(); // Ignore Ydensity
						}
						int thumbX = dis.readUnsignedByte();
						if(dos != null) dos.writeByte(thumbX);
						int thumbY = dis.readUnsignedByte();
						if(dos != null) dos.writeByte(thumbY);
						int thumbLen = thumbX * thumbY * 3;
						if(thumbLen > length-cis.count())
							throwError("Invalid header", "There should be "+thumbLen+" bytes of thumbnail but there are only "+(length-cis.count())+" bytes left in the file.");
						if(dos != null) {
							byte[] buf = new byte[thumbLen];
							dis.readFully(buf);
							dos.write(buf);
						} else 
							skipBytes(dis, thumbLen);
					} else if(type.equals("JFXX")) {
						// JFIF extension marker
						int extensionCode = dis.readUnsignedByte();
						if(extensionCode == 0x10 || extensionCode == 0x11 || extensionCode == 0x13) {
							// Alternate thumbnail, perfectly valid
							skipRest(blockLength, countAtStart, cis, dis, dos, "thumbnail frame");
							Logger.minor(this, "Thumbnail frame");
						} else
							throwError("Unknown JFXX extension "+extensionCode, "The file contains an unknown JFXX extension.");
					} else {
						if(logMINOR)
							Logger.minor(this, "Dropping application-specific APP0 chunk named "+type);
						// Application-specific extension
						if(output == null) return null;
						skipRest(blockLength, countAtStart, cis, dis, dos, "application-specific frame");
						continue; // Don't write the frame.
					}
				} else if(markerType == 0xE1) { // EXIF
					if(output == null && deleteExif) return null;
					if(deleteExif) {
						if(logMINOR)
							Logger.minor(this, "Dropping EXIF data");
						skipBytes(dis, blockLength - 2);
						continue; // Don't write the frame
					}
					skipRest(blockLength, countAtStart, cis, dis, dos, "EXIF frame");
				} else if(markerType == 0xFE) {
					// Comment
					if(output == null && deleteComments) return null;
					if(deleteComments) {
						skipBytes(dis, blockLength - 2);
						if(logMINOR)
							Logger.minor(this, "Dropping comment length "+(blockLength - 2)+'.');
						continue; // Don't write the frame
					}
					skipRest(blockLength, countAtStart, cis, dis, dos, "comment");
				} else if(markerType == 0xD9) {
					// End of image
					finished = true;
					if(logMINOR)
						Logger.minor(this, "End of image");
				} else {
					boolean valid = false;
					// We used to support only DB C4 C0, because some website said they were
					// sufficient for decoding a JPEG. Unfortunately they are not, JPEG is a 
					// very complex standard and the full spec is only available for a fee.
					// FIXME somebody who has access to the spec should have a look at this,
					// and ideally write some chunk sanitizers.
					switch(markerType) {
					// descriptions from http://svn.xiph.org/experimental/giles/jpegdump.c (GPL)
					case 0xc0: // start of frame
					case 0xc1: // extended sequential, huffman
					case 0xc2: // progressive, huffman
					case 0xc3: // lossless, huffman
					case 0xc5: // differential sequential, huffman
					case 0xc6: // differential progressive, huffman
					case 0xc7: // differential lossless, huffman
						// DELETE 0xc8 - "reserved for JPEG extension" - likely to be used for Bad Things
					case 0xc9: // extended sequential, arithmetic
					case 0xca: // progressive, arithmetic
					case 0xcb: // lossless, arithmetic
					case 0xcd: // differential sequential, arithmetic
					case 0xcf: // differential lossless, arithmetic
					case 0xc4: // define huffman tables
					case 0xcc: // define arithmetic-coding conditioning
						// Restart markers
					case 0xd0:
					case 0xd1:
					case 0xd2:
					case 0xd3:
					case 0xd4:
					case 0xd5:
					case 0xd6:
					case 0xd7:
						// Delimiters:
					case 0xd8: // start of image
					case 0xd9: // end of image
					case 0xda: // start of scan
					case 0xdb: // define quantization tables
					case 0xdc: // define number of lines
					case 0xdd: // define restart interval
					case 0xde: // define hierarchical progression
					case 0xdf: // expand reference components
						// DELETE APP0 - APP15 - application data sections, likely to be troublesome.
						// DELETE extension data sections JPG0-6,SOF48,LSE,JPG9-JPG13, JCOM (comment!!), TEM ("temporary private use for arithmetic coding")
						// DELETE 0x02 - 0xbf reserved sections.
						// Do not support JPEG2000 at the moment. Probably has different headers. FIXME.
						valid = true;
					}
					if(valid) {
						// Essential, non-terminal, but unparsed frames.
						if(blockLength < 2)
							throwError("Invalid frame length", "The file includes an invalid frame (length "+blockLength+").");
						if(dos != null) {
							byte[] buf = new byte[blockLength - 2];
							dis.readFully(buf);
							dos.write(buf);
						} else
							skipBytes(dis, blockLength - 2);
						Logger.minor(this, "Essential frame type "+Integer.toHexString(markerType)+" length "+(blockLength-2)+" offset at end "+cis.count());
					} else {
						if(markerType >= 0xE0 && markerType <= 0xEF) {
							// APP marker. Can be safely deleted.
							if(logMINOR)
								Logger.minor(this, "Dropping application marker type "+Integer.toHexString(markerType)+" length "+blockLength);
						} else {
							if(logMINOR)
								Logger.minor(this, "Dropping unknown frame type "+Integer.toHexString(markerType)+" blockLength");
						}
						// Delete frame
						skipBytes(dis, blockLength - 2);
						continue;
					}
				}
				
				if(cis.count() != countAtStart + blockLength)
					throwError("Invalid frame", "The length of the frame is incorrect (read "+
							(cis.count()-countAtStart)+" bytes, frame length "+blockLength+" for type "+Integer.toHexString(markerType)+").");
				if(dos != null) {
					// Write frame
					baos.writeTo(output);
				}
			}
			
			dis.close();
			// In future, maybe we will check the other chunks too.
			// In particular, we may want to delete, or filter, the comment blocks.
			// FIXME
		} finally {
			Closer.close(dis);
		}
		return data;
	}

	private static String l10n(String key) {
		return L10n.getString("JPEGFilter."+key);
	}

	private void writeNullTerminatedString(ByteArrayOutputStream baos, String type) throws IOException {
		try {
			byte[] data = type.getBytes("ISO-8859-1"); // ascii, near enough
			baos.write(data);
			baos.write(0);
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support ISO-8859-1: " + e, e);
		}
	}

	private String readNullTerminatedAsciiString(DataInputStream dis) throws IOException {
		StringBuilder sb = new StringBuilder();
		while(true) {
			int x = dis.read();
			if(x == -1)
				throwError("Invalid extension frame", "Could not read an extension frame name.");
			if(x == 0) break;
			char c = (char) x; // ASCII
			if(x > 128 || (c < 32 && c != 10 && c != 13))
				throwError("Invalid extension frame name", "Non-ASCII character in extension frame name");
			sb.append(c);
		}
		return sb.toString();
	}

	private void skipRest(int blockLength, long countAtStart, CountedInputStream cis, DataInputStream dis, DataOutputStream dos, String thing) throws IOException {
		// Skip the rest of the data
		int skip = (int) (blockLength - (cis.count() - countAtStart));
		if(skip < 0)
			throwError("Invalid "+thing, "The file includes an invalid "+thing+'.');
		if(skip == 0) return;
		if(dos != null) {
			byte[] buf = new byte[skip];
			dis.readFully(buf);
			dos.write(buf);
		} else {
			skipBytes(dis, skip);
		}
	}

	// FIXME factor this out somewhere ... an IOUtil class maybe
	private void skipBytes(DataInputStream dis, int skip) throws IOException {
		int skipped = 0;
		while(skipped < skip) {
			long x = dis.skip(skip - skipped);
			if(x <= 0) {
				byte[] buf = new byte[Math.min(4096, skip - skipped)];
				dis.readFully(buf);
				skipped += buf.length;
			} else
				skipped += x;
		}
	}

	private void assertHeader(DataInputStream dis, byte[] expected) throws IOException {
		byte[] read = new byte[expected.length];
		dis.readFully(read);
		if(!Arrays.equals(read, expected))
			throwError("Invalid header", "The file does not start with a valid JPEG (JFIF) header.");
	}

	private void throwError(String shortReason, String reason) throws DataFilterException {
		// Throw an exception
		String message = l10n("notJpeg");
		if(reason != null) 
			message += ' ' + reason;
		if(shortReason != null)
			message += " - " + shortReason;
		DataFilterException e = new DataFilterException(shortReason, shortReason,
				"<p>"+message+"</p>", new HTMLNode("p").addChild("#", message));
		if(Logger.shouldLog(Logger.NORMAL, this))
			Logger.normal(this, "Throwing "+e, e);
		throw e;
	}

	public Bucket writeFilter(Bucket data, BucketFactory bf, String charset, HashMap<String, String> otherParams,
	        FilterCallback cb) throws DataFilterException, IOException {
		return null;
	}

}
