package edu.umd.info.drastic;

import static org.slf4j.LoggerFactory.getLogger;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;

public class NPSFilenameUtil {
	// TODO refactor as a builder
	// NPSIdentity.fromSourceFile("NABWH_001_SG1_S19_B01_F01_D01_P003.tif").getDocObjectID()|.getDocDescrPath()|.getFolderDescrPath()
	private static final Logger LOGGER = getLogger(NPSFilenameUtil.class);

	private static Pattern hierarchy = Pattern.compile("([^_]*)_([^_]*)_([^_]*)_([^_]*)_([^_]*)_([^_]*)_([^_]*)_([^_]*)\\.\\w*");
	private static Pattern md5Sheets = Pattern.compile(".*_MD5\\.xlsx$");
	private static Pattern dcSheets = Pattern.compile("(.*/)?(.*)inventory\\.xlsx$");
	private static Pattern pageFile = Pattern.compile("(.*/)?(.*_P\\d\\d\\d)\\.tif{1,2}$");
	private static Pattern pageAccessFile = Pattern.compile("(.*/)?(.*_P\\d\\d\\d)_ACCESS\\.png$");
	private static Pattern pageFileDocId = Pattern.compile("(.*/)?(.*_D\\d\\d)_P\\d\\d\\d\\.tif{1,2}$");
	
	public static Predicate<String> PAGE_FILE_PREDICATE = pageFile.asMatchPredicate();
	public static Predicate<String> PAGE_ACCESS_FILE_PREDICATE = pageAccessFile.asMatchPredicate();
	
	public static boolean isMD5Sheet(String uri) {
		return md5Sheets.matcher(uri).matches();
	}
	
	public static boolean isDCSheet(String uri) {
		return dcSheets.matcher(uri).lookingAt();
	}
	
	public static boolean isHierarchalConvention(String uri) {
		return hierarchy.matcher(uri).lookingAt();
	}
	
	public static String getDocumentPath(String filename) {
		List<String> parts = getPathParts(filename);
	    return String.join("/", parts.subList(0, 6));
	}
	
	public static String getFolderPath(String filename) {
		List<String> parts = getPathParts(filename);
	    return String.join("/", parts.subList(0, 5));
	}
	
	// NABWH_001_SG1_S19_B01_F01_D01_P003.tif
	public static List<String> getPathParts(String filename) {
	    int idx = filename.lastIndexOf('.'); // can be -1 if not found
	    if(idx < 0) {
	      idx = filename.length();
	    }
	    String basename = filename.substring(0, idx);
	    List<String> parts = new ArrayList<String>();
	    String[] segs = basename.split("_", 0);
	    if(segs.length < 6) {
	    	LOGGER.debug("not enough path segments: {}", basename);
	    	throw new IllegalArgumentException("not enough path segments: "+basename); 
	    }
	    parts.add(segs[0]+"_"+segs[1]);
	    parts.add(segs[2]);
	    parts.add(segs[3]);
	    parts.add("BX00" + segs[4].substring(1,3));  // B01 segment to BX0001 form
	    parts.add("FL00" + segs[5].substring(1,3));  // F01 segment to FL0001 form
	    if(segs.length > 6) parts.add(segs[6]);
	    if(segs.length > 7) parts.add(segs[7]);
	    return parts;
	}

	public static String getExtractedDCFilename(String binaryLoc) {
		Matcher m = dcSheets.matcher(binaryLoc);
		if(m.find()) {
			return m.group(2)+"inventory_EXTRACTED.ttl";
		} else {
			throw new Error("Cannot find pattern.");
		}
	}
	
	public static String getAccessImageURL(String binaryLoc) {
		Matcher m = pageFile.matcher(binaryLoc);
		if(m.find()) {
			return m.group(1) + m.group(2)+"_ACCESS.png";
		} else {
			throw new Error("Cannot find pattern.");
		}
	}

	public static Object isPageFile(String i) {
		return pageFile.matcher(i).matches();
	}
	
	public static Object isPageAccessFile(String i) {
		return pageAccessFile.matcher(i).matches();
	}

	public static String getPageDocumentID(String f) {
		Matcher m = pageFileDocId.matcher(f);
		if(m.find()) {
			return m.group(2);
		} else {
			throw new Error("unexpected");
		}
	}

	public static int getPageNumber(String f) {
		List<String> parts = getPathParts(f);
		return Integer.valueOf(parts.get(6).substring(1));
	}

	public static String getThumbnailImageURL(String binaryLoc) {
		Matcher m = pageFile.matcher(binaryLoc);
		if(m.find()) {
			return m.group(1) + m.group(2)+"_THUMBNAIL.png";
		} else {
			throw new Error("Cannot find pattern.");
		}
	}

	public static void getFolderID(String docId) {
		// TODO Auto-generated method stub
		
	}

	public static URL getSubmissionUrl(URL binaryLoc) throws MalformedURLException {
		String[] segs = binaryLoc.getPath().split("/");
		return new URL(binaryLoc, "/"+String.join("/", new String[] { segs[1] , segs[2]} ));
	}
}
