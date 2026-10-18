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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.querystore.LogCapture;

/**
 * Pins {@link BootstrapLauncher}'s daemon-token contract via its {@code DaemonExecutor} seam, so the
 * launch handoff can be verified without spawning a real daemon thread or standing up Spring. The
 * launched task body (which calls {@code BootstrapService.bootstrap()} through {@link
 * org.openmrs.api.context.Context}) is deliberately NOT executed here — these tests assert that the
 * task and token are handed to the executor, not what the task does once running; the bootstrap run
 * itself is exercised by the autostart path and end-to-end.
 */
public class BootstrapLauncherTest {

	@Test
	public void launchAsync_returnsFalseAndHandsOffNothing_whenNoDaemonToken() {
		BootstrapLauncher launcher = new BootstrapLauncher();
		final int[] launches = { 0 };
		launcher.setDaemonExecutor((task, token) -> launches[0]++);

		// No token wired: the bootstrap reads global properties for its embedder/backend and so
		// cannot run without the daemon-user UserContext the token provides. The launcher must
		// refuse rather than spawn a context-less thread.
		assertFalse(launcher.launchAsync());
		assertEquals("must not hand any task to the daemon executor without a token", 0, launches[0]);
	}

	@Test
	public void launchAsync_handsTaskAndWiredTokenToDaemonExecutor_whenTokenPresent() {
		BootstrapLauncher launcher = new BootstrapLauncher();
		final Runnable[] capturedTask = { null };
		final DaemonToken[] capturedToken = { null };
		final int[] launches = { 0 };
		launcher.setDaemonExecutor((task, token) -> {
			capturedTask[0] = task;
			capturedToken[0] = token;
			launches[0]++;
		});
		DaemonToken token = new DaemonToken("token-launch");
		launcher.setDaemonToken(token);

		assertTrue(launcher.launchAsync());
		assertEquals(1, launches[0]);
		assertSame("the wired token must be the one handed to the daemon thread", token, capturedToken[0]);
		assertNotNull("a bootstrap task must be handed off to the daemon executor", capturedTask[0]);
	}

	@Test
	public void launchAsync_collapsesOverlappingRequestsToOneInFlightBootstrap() {
		BootstrapLauncher launcher = new BootstrapLauncher();
		final int[] launches = { 0 };
		// Executor that never runs the task, so the first launch stays "in flight" for the duration
		// of the test — modelling a long-running bootstrap with a second request arriving mid-run.
		launcher.setDaemonExecutor((task, token) -> launches[0]++);
		launcher.setDaemonToken(new DaemonToken("token-idempotent"));

		assertTrue(launcher.launchAsync());
		assertTrue("a duplicate launch while one is in flight is still reported as accepted",
		    launcher.launchAsync());
		assertEquals("must not spawn a second daemon bootstrap while one is in flight", 1, launches[0]);
	}

	@Test
	public void launchAsync_releasesInFlightFlag_afterTheTaskCompletes() {
		BootstrapLauncher launcher = new BootstrapLauncher();
		final int[] launches = { 0 };
		// Run the task as a daemon thread would — execute it and isolate any throwable from the
		// caller — so the task's finally runs and clears the in-flight flag. (The task's
		// Context.getService lookup throws here, with no Spring context; that stands in for "the
		// run finished" and must still release the flag.)
		launcher.setDaemonExecutor((task, token) -> {
			launches[0]++;
			try {
				task.run();
			}
			catch (RuntimeException isolatedLikeADaemonThread) {
				// swallow — a real daemon thread surfaces this via the thread-exception handler,
				// not to the launching caller
			}
		});
		launcher.setDaemonToken(new DaemonToken("token-release"));

		launcher.launchAsync();
		launcher.launchAsync();

		assertEquals("the flag must be released once a run completes, so the next request launches afresh",
		    2, launches[0]);
	}

	@Test
	public void launchAsync_releasesInFlightFlag_whenSubmissionItselfThrows() {
		BootstrapLauncher launcher = new BootstrapLauncher();
		final int[] attempts = { 0 };
		// Submission fails before the task ever reaches a thread (e.g. thread-creation failure), so
		// the task's finally cannot run — launchAsync must release the flag itself and rethrow.
		launcher.setDaemonExecutor((task, token) -> {
			attempts[0]++;
			throw new IllegalStateException("thread creation failed");
		});
		launcher.setDaemonToken(new DaemonToken("token-submit-fail"));

		try {
			launcher.launchAsync();
			fail("a submission failure must propagate, not be swallowed");
		}
		catch (IllegalStateException expected) {
			// expected
		}
		try {
			launcher.launchAsync();
			fail("retry also throws (executor still failing) — proving the flag was released, not stuck");
		}
		catch (IllegalStateException expected) {
			// expected
		}
		assertEquals("a stuck flag would short-circuit the retry as a duplicate; both attempts must"
		        + " reach the executor", 2, attempts[0]);
	}

	@Test
	public void launchResyncAsync_returnsFalseAndHandsOffNothing_whenNoDaemonToken() {
		BootstrapLauncher launcher = new BootstrapLauncher();
		final int[] launches = { 0 };
		launcher.setDaemonExecutor((task, token) -> launches[0]++);

		// Same token contract as launchAsync: the re-walk reads GPs for its embedder/backend and so
		// needs the daemon-user UserContext the token provides; refuse rather than spawn a context-less
		// thread.
		assertFalse(launcher.launchResyncAsync("obs"));
		assertEquals("must not hand any task to the daemon executor without a token", 0, launches[0]);
	}

	@Test
	public void launchResyncAsync_handsTaskAndWiredTokenToDaemonExecutor_whenTokenPresent() {
		BootstrapLauncher launcher = new BootstrapLauncher();
		final Runnable[] capturedTask = { null };
		final DaemonToken[] capturedToken = { null };
		final int[] launches = { 0 };
		launcher.setDaemonExecutor((task, token) -> {
			capturedTask[0] = task;
			capturedToken[0] = token;
			launches[0]++;
		});
		DaemonToken token = new DaemonToken("token-resync");
		launcher.setDaemonToken(token);

		assertTrue(launcher.launchResyncAsync("obs"));
		assertEquals(1, launches[0]);
		assertSame("the wired token must be the one handed to the daemon thread", token, capturedToken[0]);
		assertNotNull("a re-sync task must be handed off to the daemon executor", capturedTask[0]);
	}

	@Test
	public void launchResyncAsync_doesNotCollapse_eachCallHandsOffSeparately() {
		// Deliberate contrast with launchAsync: a per-type re-sync has no in-flight guard, so repeated
		// requests each hand off (the service serializes same-type work on its per-type lock and the
		// scan is idempotent, so a duplicate is at worst redundant, never corrupting).
		BootstrapLauncher launcher = new BootstrapLauncher();
		final int[] launches = { 0 };
		launcher.setDaemonExecutor((task, token) -> launches[0]++);
		launcher.setDaemonToken(new DaemonToken("token-resync-twice"));

		assertTrue(launcher.launchResyncAsync("obs"));
		assertTrue(launcher.launchResyncAsync("obs"));
		assertEquals("re-sync does not collapse overlapping requests (unlike launchAsync)", 2, launches[0]);
	}

	@Test
	public void launchPatientWarm_collapsesRepeatsPerPatient_andReportsWarmingUntilTheTaskEnds() {
		BootstrapLauncher launcher = new BootstrapLauncher();
		final List<Runnable> tasks = new ArrayList<>();
		launcher.setDaemonExecutor((task, token) -> tasks.add(task));
		launcher.setDaemonToken(new DaemonToken("token-warm"));

		assertTrue(launcher.launchPatientWarm("patient-1", () -> { }));
		assertTrue("a repeat for the same patient reports success", launcher.launchPatientWarm("patient-1", () -> { }));
		assertEquals("but hands off only one warm", 1, tasks.size());
		assertTrue(launcher.isWarming("patient-1"));
		assertFalse(launcher.isWarming("patient-2"));

		tasks.get(0).run();
		assertFalse(launcher.isWarming("patient-1"));
		assertTrue(launcher.launchPatientWarm("patient-1", () -> { }));
		assertEquals(2, tasks.size());
	}

	@Test
	public void launchPatientWarm_marksAThrowingWarmFailed_untilALaterOneSucceeds() {
		BootstrapLauncher launcher = SynchronousLaunchers.create();

		assertTrue(launcher.launchPatientWarm("patient-1", () -> {
			throw new IllegalStateException("projection blew up");
		}));
		assertTrue(launcher.hasFailed("patient-1"));
		assertFalse("a failed warm no longer reads as in flight", launcher.isWarming("patient-1"));
		assertFalse(launcher.hasFailed("patient-2"));

		assertTrue("a failed patient can be warmed again", launcher.launchPatientWarm("patient-1", () -> { }));
		assertFalse("and success clears the failure", launcher.hasFailed("patient-1"));
	}

	@Test
	public void launchPatientWarm_refusesBeyondTheConcurrencyCap_andWithoutAToken() {
		BootstrapLauncher launcher = new BootstrapLauncher();
		final int[] launches = { 0 };
		launcher.setDaemonExecutor((task, token) -> launches[0]++);

		assertFalse("no token, no warm", launcher.launchPatientWarm("patient-0", () -> { }));

		launcher.setDaemonToken(new DaemonToken("token-warm-cap"));
		for (int i = 1; i <= BootstrapLauncher.DEFAULT_MAX_CONCURRENT_PROJECTIONS; i++) {
			assertTrue(launcher.launchPatientWarm("patient-" + i, () -> { }));
		}
		assertFalse("one past the cap is refused", launcher.launchPatientWarm("patient-x", () -> { }));
		assertTrue("with every slot busy, a repeat for a patient whose warm holds one is still in flight",
		    launcher.launchPatientWarm("patient-1", () -> { }));
		assertFalse(launcher.isWarming("patient-x"));
		assertEquals(BootstrapLauncher.DEFAULT_MAX_CONCURRENT_PROJECTIONS, launches[0]);
	}

	@Test
	public void launchPatientWarm_logsStartAndDuplicateAtDebug_withoutThePatientUuid() {
		BootstrapLauncher launcher = new BootstrapLauncher();
		launcher.setDaemonExecutor((task, token) -> { });
		launcher.setDaemonToken(new DaemonToken("token-warm-log"));

		try (LogCapture log = LogCapture.of(BootstrapLauncher.class)) {
			launcher.launchPatientWarm("patient-uuid-1", () -> { });
			launcher.launchPatientWarm("patient-uuid-1", () -> { });

			List<String> debug = log.at("DEBUG");
			assertEquals(2, debug.size());
			assertTrue(debug.get(0), debug.get(0).startsWith("Patient warm starting"));
			assertTrue(debug.get(1), debug.get(1).contains("ignoring duplicate"));
			for (String line : log.lines()) {
				assertFalse(line, line.contains("patient-uuid-1"));
			}
		}
	}

	@Test
	public void launchPatientWarm_failureWarnsWithoutThePatientUuid_andNamesItOnlyAtDebug() {
		BootstrapLauncher launcher = SynchronousLaunchers.create();

		try (LogCapture log = LogCapture.of(BootstrapLauncher.class)) {
			launcher.launchPatientWarm("patient-uuid-2", () -> {
				throw new IllegalStateException("boom");
			});

			assertEquals(1, log.at("WARN").size());
			assertFalse(log.at("WARN").get(0).contains("patient-uuid-2"));
			assertTrue(log.at("DEBUG").stream().anyMatch(l -> l.contains("patient-uuid-2")));
		}
	}

	@Test
	public void syncProjections_andBackgroundWarms_shareOneBound() {
		List<Runnable> parked = new ArrayList<>();
		BootstrapLauncher launcher = SynchronousLaunchers.parked(1, parked);
		final int[] ran = { 0 };

		assertTrue("the one slot goes to a background warm", launcher.launchPatientWarm("patient-1", () -> { }));
		assertFalse("a synchronous projection finds no slot in time", launcher.runWithProjectionSlot("patient-sync", () -> ran[0]++, 50));
		assertFalse("nor does a second warm", launcher.launchPatientWarm("patient-2", () -> { }));
		assertEquals(0, ran[0]);

		parked.get(0).run();
		assertTrue("the warm released its slot", launcher.runWithProjectionSlot("patient-sync", () -> ran[0]++, 50));
		assertEquals(1, ran[0]);
		assertTrue("and the synchronous projection released it in turn", launcher.launchPatientWarm("patient-2", () -> { }));
	}

	@Test
	public void runWithProjectionSlot_releasesTheSlotWhenTheWorkThrows() {
		List<Runnable> parked = new ArrayList<>();
		BootstrapLauncher launcher = SynchronousLaunchers.parked(1, parked);

		try {
			launcher.runWithProjectionSlot("patient-sync", () -> {
				throw new IllegalStateException("projection blew up");
			}, 50);
			fail("expected the work's exception");
		}
		catch (IllegalStateException expected) {
			// pass
		}
		assertTrue(launcher.launchPatientWarm("patient-1", () -> { }));
	}

	@Test
	public void failedPatients_areBounded_oldestForgottenFirst() {
		BootstrapLauncher launcher = SynchronousLaunchers.create();
		for (int i = 0; i <= BootstrapLauncher.MAX_FAILED_PATIENTS; i++) {
			launcher.launchPatientWarm("patient-" + i, () -> {
				throw new IllegalStateException("x");
			});
		}

		assertFalse("the first failure is forgotten past the bound", launcher.hasFailed("patient-0"));
		assertTrue(launcher.hasFailed("patient-" + BootstrapLauncher.MAX_FAILED_PATIENTS));
	}
}
