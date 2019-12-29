/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.spring.initializr.web.controller;

import java.util.Map;

import io.spring.initializr.web.AbstractInitializrControllerIntegrationTests;
import io.spring.initializr.web.AbstractInitializrIntegrationTests;
import io.spring.initializr.web.mapper.InitializrMetadataVersion;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;
import org.skyscreamer.jsonassert.comparator.DefaultComparator;
import org.skyscreamer.jsonassert.comparator.JSONCompareUtil;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ProjectMetadataController}.
 *
 * @author Stephane Nicoll
 */
@ActiveProfiles("test-default")
public class ProjectMetadataControllerIntegrationTests extends AbstractInitializrControllerIntegrationTests {

	@Test
	void metadataWithNoAcceptHeader() {
		// rest template sets application/json by default
		ResponseEntity<String> response = invokeHome(null, "*/*");
		validateCurrentMetadata(response);
	}

	@Test
	void currentMetadataCompatibleWithV2() {
		ResponseEntity<String> response = invokeHome(null, "*/*");
		validateMetadata(response, AbstractInitializrIntegrationTests.CURRENT_METADATA_MEDIA_TYPE, "2.0.0",
				new LenientArraySubsetComparator());
	}

	@Test
	void metadataWithV2AcceptHeader() {
		ResponseEntity<String> response = invokeHome(null, "application/vnd.initializr.v2+json");
		validateMetadata(response, InitializrMetadataVersion.V2.getMediaType(), "2.0.0", JSONCompareMode.STRICT);
	}

	@Test
	void metadataWithInvalidPlatformVersion() {
		try {
			execute("/dependencies?bootVersion=1.5.17.RELEASE", String.class, "application/vnd.initializr.v2.1+json",
					"application/json");
		}
		catch (HttpClientErrorException ex) {
			assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(ex.getResponseBodyAsString().contains("1.5.17.RELEASE"));
		}
	}

	@Test
	void metadataWithCurrentAcceptHeader() {
		getRequests().setFields("_links.maven-project", "dependencies.values[0]", "type.values[0]",
				"javaVersion.values[0]", "packaging.values[0]", "bootVersion.values[0]", "language.values[0]");
		ResponseEntity<String> response = invokeHome(null, "application/vnd.initializr.v2.1+json");
		assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isNotNull();
		validateContentType(response, AbstractInitializrIntegrationTests.CURRENT_METADATA_MEDIA_TYPE);
		validateCurrentMetadata(response.getBody());
	}

	@Test
	void metadataWithSeveralAcceptHeader() {
		ResponseEntity<String> response = invokeHome(null, "application/vnd.initializr.v2.1+json",
				"application/vnd.initializr.v2+json");
		validateContentType(response, AbstractInitializrIntegrationTests.CURRENT_METADATA_MEDIA_TYPE);
		validateCurrentMetadata(response.getBody());
	}

	@Test
	void metadataWithHalAcceptHeader() {
		ResponseEntity<String> response = invokeHome(null, "application/hal+json");
		assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isNotNull();
		validateContentType(response, ProjectMetadataController.HAL_JSON_CONTENT_TYPE);
		validateCurrentMetadata(response.getBody());
	}

	@Test
	void metadataWithUnknownAcceptHeader() {
		try {
			invokeHome(null, "application/vnd.initializr.v5.4+json");
		}
		catch (HttpClientErrorException ex) {
			assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
		}
	}

	@Test
	void homeIsJson() {
		String body = invokeHome(null, (String[]) null).getBody();
		assertThat(body).contains("\"dependencies\"");
	}

	@Test
	void unknownAgentReceivesJsonByDefault() {
		ResponseEntity<String> response = invokeHome("foo/1.0", "*/*");
		validateCurrentMetadata(response);
	}

	@Test
	// Test that the current output is exactly what we expect
	void validateCurrentProjectMetadata() {
		validateCurrentMetadata(getMetadataJson());
	}

	private String getMetadataJson() {
		return getMetadataJson(null);
	}

	private String getMetadataJson(String userAgentHeader, String... acceptHeaders) {
		return invokeHome(userAgentHeader, acceptHeaders).getBody();
	}

	/**
	 * Leniently performs {@code actual.contains(expected)}-style comparisons on any child
	 * arrays.
	 *
	 * Adapted from {@link org.skyscreamer.jsonassert.comparator.ArraySizeComparator}
	 */
	private static class LenientArraySubsetComparator extends DefaultComparator {

		LenientArraySubsetComparator() {
			super(JSONCompareMode.LENIENT);
		}

		@Override
		public void compareJSONArray(String prefix, JSONArray expected, JSONArray actual, JSONCompareResult result)
				throws JSONException {
			if (expected.length() > actual.length()) {
				result.fail(prefix + "[]: Expected array containing " + expected.length()
						+ " items to be a subset of array containing " + actual.length() + "items");
				return;
			}
			if (expected.length() == 0) {
				return; // Nothing to compare
			}

			String uniqueKey = JSONCompareUtil.findUniqueKey(actual);
			Map<Object, JSONObject> expectedObjMap = JSONCompareUtil.arrayOfJsonObjectToMap(expected, uniqueKey);
			Map<Object, JSONObject> actualObjMap = JSONCompareUtil.arrayOfJsonObjectToMap(actual, uniqueKey);

			for (int i = 0; i < actual.length(); i++) {
				Object objId = actual.getJSONObject(i).opt(uniqueKey);
				if (!expectedObjMap.containsKey(objId)) {
					actualObjMap.remove(objId);
				}
			}
			JSONArray strippedActual = new JSONArray(actualObjMap.values());

			if (JSONCompareUtil.allSimpleValues(expected)) {
				compareJSONArrayOfSimpleValues(prefix, expected, strippedActual, result);
			}
			else if (JSONCompareUtil.allJSONObjects(expected)) {
				compareJSONArrayOfJsonObjects(prefix, expected, strippedActual, result);
			}
			else {
				// An expensive last resort
				recursivelyCompareJSONArray(prefix, expected, strippedActual, result);
			}
		}

	}

}
