package edu.umd.info.drastic;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class DigestHeaderTest {

    @Test
    public void testPOSTFileWithoutDigest() {
	final File file = new File(getClass().getClassLoader()
	    .getResource("test.txt").getFile());
	assertNotNull(file);
	assertTrue(file.canRead());
	given().
	  multiPart(file).
	  when().
	  post("/")
	  .then().statusCode(201);
    }

    @Test
    public void testPUTFileWithoutDigest() {
	final File file = new File(getClass().getClassLoader()
	    .getResource("test.txt").getFile());
	assertNotNull(file);
	assertTrue(file.canRead());
	given().
	  multiPart(file).
	  when().
	  put("/afile-"+UUID.randomUUID().toString())
	  .then().statusCode(201);
    }
    
    @Test
    public void testPOSTFileWithIncorrectDigest() {
	final File file = new File(getClass().getClassLoader()
	    .getResource("test.txt").getFile());
	assertNotNull(file);
	assertTrue(file.canRead());
	given().
	  header("Digest", "md5=thatisnotright").
	  multiPart(file).
	  when().
	  post("/")
	  .then().statusCode(409);
    }
    
    @Test
    public void testPOSTFileWithCorrectDigest() {
	final File file = new File(getClass().getClassLoader()
	    .getResource("test.txt").getFile());
	assertNotNull(file);
	assertTrue(file.canRead());
	given().
	  header("Digest", "md5=1B2M2Y8AsgTpgAmY7PhCfg==").
	  multiPart(file).
	  when().
	  post("/")
	  .then().statusCode(201);
    }
    
    @Test
    public void testPUTFileWithCorrectDigest() {
	final File file = new File(getClass().getClassLoader()
	    .getResource("test.txt").getFile());
	assertNotNull(file);
	assertTrue(file.canRead());
	given().
	  header("Digest", "md5=1B2M2Y8AsgTpgAmY7PhCfg==").
	  multiPart(file).
	  when().
	  put("/afile-"+UUID.randomUUID().toString())
	  .then().statusCode(201);
    }
    
    @Test
    public void testPUTFileWithIncorrectDigest() {
	final File file = new File(getClass().getClassLoader()
	    .getResource("test.txt").getFile());
	assertNotNull(file);
	assertTrue(file.canRead());
	given().
	  header("Digest", "md5=thisisnotright").
	  multiPart(file).
	  when().
	  put("/afile-"+UUID.randomUUID().toString())
	  .then().statusCode(409);
    }
    
    @Test
    public void testPUTFileWithUnsupportedDigestAlgorithm() {
	final File file = new File(getClass().getClassLoader()
	    .getResource("test.txt").getFile());
	assertNotNull(file);
	assertTrue(file.canRead());
	given().
	  header("Digest", "foo=thisisnotright").
	  multiPart(file).
	  when().
	  put("/afile-"+UUID.randomUUID().toString())
	  .then().statusCode(400);
    }
}
