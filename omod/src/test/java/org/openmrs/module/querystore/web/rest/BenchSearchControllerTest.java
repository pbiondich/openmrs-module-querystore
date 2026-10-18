/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.web.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;
import org.mockito.InOrder;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** BENCHMARK ONLY: the tests that came with 4f1a8ea, moved with the endpoint to its own controller. */
public class BenchSearchControllerTest {

	private final BenchSearchController controller = new BenchSearchController();

	@After
	public void clearContext() {
		Context.clearUserContext();
	}

	@Test
	public void benchSearch_returnsRowsInContractKeyOrderWithTimingHeader() {
		authenticate();
		// BENCHMARK ONLY: a Python client depends on the exact keys, their order, and the header.
		QueryStoreService queryStore = mock(QueryStoreService.class);
		QueryDocument dated = new QueryDocument();
		dated.setResourceType("obs");
		dated.setResourceUuid("obs-uuid");
		dated.setDate(LocalDate.of(2024, 1, 31));
		dated.setText("Weight: 70 kg");
		QueryDocument undated = new QueryDocument();
		undated.setResourceType("allergy");
		undated.setResourceUuid("allergy-uuid");
		undated.setText("Allergy: penicillin");
		when(queryStore.searchByPatient("patient-uuid", "weight", 50)).thenReturn(Arrays.asList(dated, undated));
		controller.setQueryStoreService(queryStore);

		ResponseEntity<Object> response = controller.benchSearch("patient-uuid", "weight", 50, false);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		String millis = response.getHeaders().getFirst("X-Querystore-Millis");
		assertNotNull(millis);
		assertTrue(Long.parseLong(millis) >= 0);
		List<?> rows = (List<?>) response.getBody();
		assertEquals(2, rows.size());
		Map<?, ?> first = (Map<?, ?>) rows.get(0);
		assertEquals(Arrays.asList("resourceType", "resourceUuid", "date", "text"), new ArrayList<Object>(first.keySet()));
		assertEquals("obs", first.get("resourceType"));
		assertEquals("obs-uuid", first.get("resourceUuid"));
		assertEquals("2024-01-31", first.get("date"));
		assertEquals("Weight: 70 kg", first.get("text"));
		Map<?, ?> second = (Map<?, ?>) rows.get(1);
		assertTrue(second.containsKey("date"));
		assertNull(second.get("date"));
	}

	@Test
	public void benchSearch_warm_neverDeletes() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		when(queryStore.searchByPatient("patient-uuid", "weight", 50)).thenReturn(Collections.<QueryDocument> emptyList());
		controller.setQueryStoreService(queryStore);

		controller.benchSearch("patient-uuid", "weight", 50, false);

		verify(queryStore, never()).bulkDeleteByPatient(anyString());
		verify(queryStore).searchByPatient("patient-uuid", "weight", 50);
	}

	@Test
	public void benchSearch_cold_deletesBeforeSearching() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		when(queryStore.searchByPatient("patient-uuid", "weight", 50)).thenReturn(Collections.<QueryDocument> emptyList());
		controller.setQueryStoreService(queryStore);

		controller.benchSearch("patient-uuid", "weight", 50, true);

		InOrder order = inOrder(queryStore);
		order.verify(queryStore).bulkDeleteByPatient("patient-uuid");
		order.verify(queryStore).searchByPatient("patient-uuid", "weight", 50);
	}

	@Test
	public void benchSearch_returns400_whenQueryBlank() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		controller.setQueryStoreService(queryStore);

		ResponseEntity<Object> response = controller.benchSearch("patient-uuid", "   ", 50, true);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertNotNull(errorOf(response));
		verifyNoInteractions(queryStore);
	}

	@Test
	public void benchSearch_returns400_whenPatientBlank() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		controller.setQueryStoreService(queryStore);

		ResponseEntity<Object> response = controller.benchSearch(null, "weight", 50, true);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		verifyNoInteractions(queryStore);
	}

	@Test
	public void benchSearch_clampsLimitTo1Through200() {
		authenticate();
		QueryStoreService queryStore = mock(QueryStoreService.class);
		when(queryStore.searchByPatient(anyString(), anyString(), anyInt())).thenReturn(Collections.<QueryDocument> emptyList());
		controller.setQueryStoreService(queryStore);

		controller.benchSearch("patient-uuid", "weight", 0, false);
		controller.benchSearch("patient-uuid", "weight", -5, false);
		controller.benchSearch("patient-uuid", "weight", 1000, false);

		verify(queryStore, times(2)).searchByPatient("patient-uuid", "weight", 1);
		verify(queryStore).searchByPatient("patient-uuid", "weight", 200);
	}

	@Test
	public void benchSearch_withoutGetPatients_isRejectedBeforeAnyDelete() {
		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String privilege) {
				return false;
			}
		});
		QueryStoreService queryStore = mock(QueryStoreService.class);
		controller.setQueryStoreService(queryStore);
		try {
			controller.benchSearch("patient-uuid", "weight", 50, true);
			fail("expected ContextAuthenticationException");
		}
		catch (ContextAuthenticationException expected) {
			verifyNoInteractions(queryStore);
		}
	}

	@Test
	public void benchSearch_cold_withoutManageGlobalProperties_isRejectedBeforeDelete() {
		// Holds Get Patients but not Manage Global Properties: a warm search is fine, a cold one is not.
		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String privilege) {
				return PrivilegeConstants.GET_PATIENTS.equals(privilege);
			}
		});
		QueryStoreService queryStore = mock(QueryStoreService.class);
		controller.setQueryStoreService(queryStore);
		try {
			controller.benchSearch("patient-uuid", "weight", 50, true);
			fail("expected ContextAuthenticationException");
		}
		catch (ContextAuthenticationException expected) {
			verifyNoInteractions(queryStore);
		}
	}

	@Test
	public void benchSearch_mapsAuthFailuresAndUnexpectedErrorsToCleanStatuses() {
		Context.setUserContext(new UserContext(null));
		assertEquals(HttpStatus.UNAUTHORIZED, controller.handleAuthFailure(new ContextAuthenticationException("x")).getStatusCode());
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, controller.handleUnexpected(new RuntimeException("x")).getStatusCode());
	}

	private static void authenticate() {
		Context.setUserContext(new UserContext(null) {

			@Override
			public User getAuthenticatedUser() {
				return new User();
			}

			@Override
			public boolean isAuthenticated() {
				return true;
			}

			@Override
			public boolean hasPrivilege(String privilege) {
				return true;
			}
		});
	}

	private static String errorOf(ResponseEntity<Object> response) {
		return (String) ((Map<?, ?>) response.getBody()).get("error");
	}
}
