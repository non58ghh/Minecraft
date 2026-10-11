package com.aicivilization.observer;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S3SignerTest {

	/** The worked "GET Object" example from AWS's Signature Version 4 documentation for S3. */
	@Test
	void matchesTheDocumentedExample() {
		S3Signer signer = new S3Signer("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
		String auth = signer.authorization("GET", "/test.txt", Map.of(
				"Host", "examplebucket.s3.amazonaws.com",
				"Range", "bytes=0-9",
				"x-amz-content-sha256", S3Signer.EMPTY_SHA256,
				"x-amz-date", "20130524T000000Z"), S3Signer.EMPTY_SHA256, "20130524T000000Z");
		assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
				+ "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
				+ "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", auth);
	}
}
