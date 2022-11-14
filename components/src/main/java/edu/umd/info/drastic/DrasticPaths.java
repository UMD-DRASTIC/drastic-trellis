package edu.umd.info.drastic;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public enum DrasticPaths {
	name_authority("/name-authority/"),
	descriptions("/descriptions/"),
	submissions("/submissions/");
	
	public String path;
	public Pattern pattern;
	
	DrasticPaths(String path) {
		this.path = path;
		this.pattern = Pattern.compile(path+"*");
	}
	
	boolean matches(String uri) {
		try {
			URI foo = new URI(uri);
			return this.pattern.matcher(foo.getPath()).matches();
		} catch( URISyntaxException e) {
			return false;
		}
	}
	
	boolean matches(URI uri) {
		return this.pattern.matcher(uri.getPath()).matches();
	}
}
