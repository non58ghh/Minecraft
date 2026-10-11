package com.aicivilization.observer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for S3-compatible object storage (Cloudflare R2
 * among them): the {@code Authorization} header for one request. Only what
 * plain object uploads need: no query parameters, headers given in full.
 */
final class S3Signer {

	static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	private final String accessKeyId;
	private final byte[] secretKey;
	private final String region;

	S3Signer(String accessKeyId, String secretAccessKey, String region) {
		this.accessKeyId = accessKeyId;
		this.secretKey = ("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8);
		this.region = region;
	}

	/**
	 * @param amzDate  the request time as {@code yyyyMMdd'T'HHmmss'Z'}, also sent as {@code x-amz-date}
	 * @param path     the URI path, already percent-encoded as it will be sent
	 * @param headers  every header to sign (host, x-amz-date and x-amz-content-sha256 at least), by name
	 */
	String authorization(String method, String path, Map<String, String> headers, String payloadSha256, String amzDate) {
		TreeMap<String, String> sorted = new TreeMap<>();
		headers.forEach((k, v) -> sorted.put(k.toLowerCase(java.util.Locale.ROOT), v.strip()));
		StringBuilder canonicalHeaders = new StringBuilder();
		sorted.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v).append('\n'));
		String signedHeaders = String.join(";", sorted.keySet());
		String canonicalRequest = method + "\n" + path + "\n" + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadSha256;
		String day = amzDate.substring(0, 8);
		String scope = day + "/" + region + "/s3/aws4_request";
		String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
		byte[] key = hmac(hmac(hmac(hmac(secretKey, day), region), "s3"), "aws4_request");
		String signature = HexFormat.of().formatHex(hmac(key, toSign));
		return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope + ", SignedHeaders=" + signedHeaders
				+ ", Signature=" + signature;
	}

	static String sha256Hex(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] hmac(byte[] key, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
