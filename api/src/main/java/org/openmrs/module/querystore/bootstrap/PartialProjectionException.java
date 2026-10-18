/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A per-patient projection finished every type it could and failed at least one. Thrown after the
 * other types have run, so the chart holds everything that projected; the caller learns the chart
 * is incomplete, and a warm that sees it reads as failed rather than indexed.
 */
public class PartialProjectionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final List<String> failedTypes;

	public PartialProjectionException(List<String> failedTypes) {
		super("Per-patient projection failed for " + failedTypes.size() + " type(s): " + failedTypes);
		this.failedTypes = Collections.unmodifiableList(new ArrayList<>(failedTypes));
	}

	public List<String> getFailedTypes() {
		return failedTypes;
	}
}
