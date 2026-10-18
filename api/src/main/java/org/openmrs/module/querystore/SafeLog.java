/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore;

import org.apache.commons.logging.Log;
import org.slf4j.Logger;

/**
 * Logs a failure at WARN or ERROR without the exception's stack or any uuid in its messages: an
 * exception message can quote anything (a Hibernate "No row with the given identifier exists", a
 * backend error echoing a document), and WARN and ERROR reach production logs by default. The line
 * carries the exception types and their uuid-scrubbed messages; the full throwable goes to DEBUG,
 * like the [QSEVAL] lines.
 */
public final class SafeLog {

	// Enough of a cause chain to name the real failure, without walking a pathological one.
	private static final int MAX_CAUSES = 4;

	private SafeLog() {
	}

	public static void warn(Log log, String message, Throwable t) {
		log.warn(message + describe(t));
		log.debug(message, t);
	}

	public static void error(Log log, String message, Throwable t) {
		log.error(message + describe(t));
		log.debug(message, t);
	}

	public static void warn(Logger log, String message, Throwable t) {
		log.warn(message + describe(t));
		log.debug(message, t);
	}

	public static void error(Logger log, String message, Throwable t) {
		log.error(message + describe(t));
		log.debug(message, t);
	}

	/** {@code " [Type: message; caused by Type: message]"}, uuids scrubbed; empty for no throwable. */
	static String describe(Throwable t) {
		if (t == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(" [");
		int depth = 0;
		for (Throwable c = t; c != null && depth < MAX_CAUSES; c = c.getCause() == c ? null : c.getCause(), depth++) {
			if (depth > 0) {
				sb.append("; caused by ");
			}
			sb.append(c.getClass().getName());
			if (c.getMessage() != null) {
				sb.append(": ").append(SkipLogFormat.scrubUuids(c.getMessage()).replace('\n', ' ').replace('\r', ' '));
			}
		}
		return sb.append(" (stack at DEBUG)]").toString();
	}
}
