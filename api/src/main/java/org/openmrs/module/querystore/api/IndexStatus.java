/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.api;

import java.util.Locale;

/**
 * Whether a patient's chart is in the read store yet. Checked in this order, so a patient whose
 * background projection has written some types but not all reads as {@link #PROJECTING}, not
 * {@link #INDEXED}.
 */
public enum IndexStatus {

	/** A background warm ({@link QueryStoreService#warm}) is projecting the chart now. */
	PROJECTING,

	/** At least one document is indexed for the patient. */
	INDEXED,

	/** Nothing is indexed and no projection is running. */
	NOT_INDEXED;

	/** Wire form: {@code indexed}, {@code projecting}, {@code not_indexed}. */
	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
