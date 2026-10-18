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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.bootstrap.BootstrapService;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * BENCHMARK ONLY: the bench search through Spring's real dispatch, so its 403, 400 and 404 come
 * from its own {@code @ExceptionHandler}s and removing one fails a test here.
 */
public class BenchSearchDispatchTest {

	private static final String PATIENT = "dd75c020-1691-11df-97a5-7038c432aabf";

	private MockMvc mvc;

	@Before
	public void setUp() {
		BenchSearchController controller = new BenchSearchController();
		PatientService patients = mock(PatientService.class);
		when(patients.getPatientByUuid(PATIENT)).thenReturn(new Patient());
		controller.setPatientService(patients);
		BootstrapService bootstrap = mock(BootstrapService.class);
		when(bootstrap.getResourceTypeNames()).thenReturn(Arrays.asList("obs", "condition"));
		controller.setBootstrapService(bootstrap);
		controller.setQueryStoreService(mock(QueryStoreService.class));
		mvc = MockMvcBuilders.standaloneSetup(controller).setMessageConverters(new MappingJackson2HttpMessageConverter())
		        .build();
	}

	@After
	public void clearContext() {
		Context.clearUserContext();
	}

	@Test
	public void coldWithoutManageGlobalProperties_gets403() throws Exception {
		signIn(PrivilegeConstants.GET_PATIENTS);

		mvc.perform(get("/rest/v1/querystore/search").param("patient", PATIENT).param("q", "cd4").param("cold", "true"))
		        .andExpect(status().isForbidden());
	}

	@Test
	public void anOverlongQuery_gets400() throws Exception {
		signIn(null);
		StringBuilder q = new StringBuilder();
		for (int i = 0; i <= PatientRequests.MAX_QUERY_LENGTH; i++) {
			q.append('a');
		}

		mvc.perform(get("/rest/v1/querystore/search").param("patient", PATIENT).param("q", q.toString()))
		        .andExpect(status().isBadRequest());
	}

	@Test
	public void anUnknownPatient_gets404() throws Exception {
		signIn(null);

		mvc.perform(get("/rest/v1/querystore/search").param("patient", "00000000-0000-4000-8000-000000000000").param("q",
		    "cd4")).andExpect(status().isNotFound());
	}

	/** Signs in a user holding only {@code privilege}, or every privilege when it is null. */
	private static void signIn(String privilege) {
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
			public boolean hasPrivilege(String p) {
				return privilege == null || privilege.equals(p);
			}
		});
	}
}
