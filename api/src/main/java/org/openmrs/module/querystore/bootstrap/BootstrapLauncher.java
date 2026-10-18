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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.querystore.SafeLog;

/**
 * Single home for "run the global {@link BootstrapService#bootstrap()} on an OpenMRS daemon thread".
 * Both callers — {@code QueryStoreActivator} autostart and the on-demand reindex REST endpoint
 * ({@code POST /querystore/reindex} with {@code scope:"all"}) — go through here so the daemon-thread
 * launch lives in exactly one place.
 *
 * <p>A {@link DaemonToken} is required: the bootstrap reads global properties for its embedder and
 * backend, which needs the daemon-user {@link org.openmrs.api.context.UserContext} the token sets up
 * on the spawned thread. Until the activator wires the token, {@link #launchAsync()} refuses (returns
 * {@code false}) rather than spawning a context-less thread that would silently misbehave — the
 * caller surfaces that to its own caller (the activator logs and skips; the endpoint returns 503).
 *
 * <p>The token is wired by the activator the same way the sync dispatcher's is (eagerly from
 * {@code setDaemonToken} and again from {@code started()}), mirroring
 * {@link org.openmrs.module.querystore.sync.AfterCommitDispatcher}. The {@link DaemonExecutor} seam
 * over the static {@link Daemon#runInDaemonThread} call lets tests verify the launch handoff without
 * spawning a real thread, again mirroring the dispatcher.
 */
public class BootstrapLauncher {

	private static final Log log = LogFactory.getLog(BootstrapLauncher.class);

	private volatile DaemonToken daemonToken;

	private volatile DaemonExecutor daemonExecutor = Daemon::runInDaemonThread;

	// Collapses overlapping launch requests (autostart racing the endpoint, an admin double-click, a
	// retried script) to a single in-flight run. BootstrapServiceImpl's per-type locks already keep
	// concurrent runs from corrupting progress bookkeeping; this additionally stops redundant daemon
	// threads from piling up parked on those locks and re-scanning an already-complete cursor.
	private final AtomicBoolean running = new AtomicBoolean(false);

	/** Default for {@code querystore.projection.maxConcurrent}. */
	public static final int DEFAULT_MAX_CONCURRENT_PROJECTIONS = 4;

	// How many failed patients are remembered; the oldest is forgotten past this, and reads as not
	// indexed again.
	static final int MAX_FAILED_PATIENTS = 10_000;

	// Every per-patient projection holds one of these while it runs: a background warm, and a
	// synchronous cold-touch projection on a request thread alike. Daemon spawns a thread per warm and
	// each projection saturates the CPU's cores through the embedder, so without one bound a burst of
	// cold patients would run that many at once. Replaced only by setMaxConcurrentProjections, at
	// module start.
	private volatile Semaphore projectionSlots = new Semaphore(DEFAULT_MAX_CONCURRENT_PROJECTIONS);

	// Patients with a projection in flight (a background warm, or a synchronous one holding a slot):
	// the per-patient in-flight guard, and the source of the "projecting" index status.
	private final Set<String> warmingPatients = ConcurrentHashMap.newKeySet();

	// Patients whose last warm failed: the source of the "failed" index status. Bounded, least
	// recently failed first out, so a caller warming uuids that never index cannot grow it forever.
	private final Set<String> failedPatients = Collections.synchronizedSet(Collections.newSetFromMap(
	    new LinkedHashMap<String, Boolean>(16, 0.75f, true) {

		    private static final long serialVersionUID = 1L;

		    @Override
		    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
			    return size() > MAX_FAILED_PATIENTS;
		    }
	    }));

	/**
	 * Sets how many per-patient projections may run at once ({@code querystore.projection.maxConcurrent}).
	 * Called once at module start, before any projection; a later call does not shrink projections
	 * already holding a slot.
	 */
	public void setMaxConcurrentProjections(int max) {
		this.projectionSlots = new Semaphore(Math.max(1, max));
	}

	/**
	 * Runs one patient's projection ({@code work}) on the calling thread once a projection slot is
	 * free, waiting at most {@code timeoutMillis} for one. Backs the synchronous cold-touch projection,
	 * so it shares the bound with background warms. While it runs the patient reads as warming, as
	 * for a background warm: the chart is half written until it finishes, and a concurrent status
	 * read or non-waiting search must not take it as indexed. If a background warm for the patient is
	 * already registered, that entry stands and this one adds none.
	 *
	 * @return {@code false} when no slot came free in time (or the wait was interrupted), in which
	 *         case {@code work} did not run
	 */
	public boolean runWithProjectionSlot(String patientUuid, Runnable work, long timeoutMillis) {
		Semaphore slots = this.projectionSlots;
		try {
			if (!slots.tryAcquire(Math.max(0, timeoutMillis), TimeUnit.MILLISECONDS)) {
				log.debug("No projection slot came free in time; not projecting");
				return false;
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		boolean registered = patientUuid != null && warmingPatients.add(patientUuid);
		try {
			work.run();
			return true;
		}
		finally {
			if (registered) {
				warmingPatients.remove(patientUuid);
			}
			slots.release();
		}
	}

	public void setDaemonToken(DaemonToken token) {
		this.daemonToken = token;
	}

	/**
	 * Ensures a global {@link BootstrapService#bootstrap()} is running on a daemon thread, returning
	 * immediately. If one is already in flight, this is a no-op that still reports success — the
	 * caller's intent ("a global reindex is underway") is satisfied either way, and progress is
	 * observed out-of-band via the indexing-status endpoint.
	 *
	 * <p>{@code BootstrapServiceImpl.bootstrap()} catches per-type failures internally and never
	 * rethrows; the task wraps it only to clear the in-flight flag in a {@code finally}, so an
	 * unexpected throw (e.g. service lookup failure) still releases the flag and surfaces via the
	 * framework's thread-exception handler.
	 *
	 * @return {@code true} once a bootstrap is in flight (newly launched or already running);
	 *         {@code false} only when no {@link DaemonToken} is wired yet, in which case nothing runs.
	 */
	public boolean launchAsync() {
		DaemonToken token = this.daemonToken;
		if (token == null) {
			log.warn("Daemon token unavailable; cannot launch bootstrap "
			        + "(BootstrapService.bootstrap() can still be called programmatically)");
			return false;
		}
		// CAS before the submit below (not after) so two racing callers can't both launch: the loser
		// sees running==true and collapses onto the in-flight run. Accepted trade-off: if execute()
		// throws a synchronous RuntimeException (near-impossible — a valid token is not rejected, and
		// native-thread exhaustion surfaces as an OOM Error, not caught here) a duplicate that already
		// observed running==true reports success while this launch fails. Moving the CAS after submit
		// would close that window but reintroduce double-launch, the worse defect — so do not.
		if (!running.compareAndSet(false, true)) {
			log.info("Bootstrap already in flight; ignoring duplicate launch request");
			return true;
		}
		try {
			// Resolve the service on the daemon thread, where the token has set up the UserContext
			// the bootstrap's GP reads need — not on the calling thread.
			daemonExecutor.execute(() -> {
				try {
					log.info("Bootstrap starting");
					Context.getService(BootstrapService.class).bootstrap();
					log.info("Bootstrap completed");
				}
				finally {
					running.set(false);
				}
			}, token);
		}
		catch (RuntimeException e) {
			// The task never made it onto a thread (e.g. thread-creation/token failure), so its
			// finally cannot release the flag. Release it here, else every future launch is stuck
			// being rejected as a duplicate, and rethrow so the failure isn't swallowed.
			running.set(false);
			throw e;
		}
		return true;
	}

	/**
	 * Launches a targeted re-sync of a single resource type on a daemon thread (reconciliation
	 * remediation; see {@link BootstrapService#resyncType(String)}). Like {@link #launchAsync()} it
	 * needs the {@link DaemonToken} for the daemon-user {@code UserContext} the scan's GP reads
	 * require, and resolves the service on the daemon thread.
	 *
	 * <p>No global running-flag guard (unlike {@link #launchAsync()}): a re-sync is scoped to one
	 * type, the service serializes same-type work on its per-type lock, and the scan is idempotent —
	 * so a duplicate request is at worst redundant, not corrupting. The caller is expected to have
	 * validated {@code resourceType} (e.g. against {@link BootstrapService#getResourceTypeNames()}).
	 *
	 * @return {@code true} once the re-sync is handed to a daemon thread; {@code false} only when no
	 *         {@link DaemonToken} is wired yet, in which case nothing runs.
	 */
	public boolean launchResyncAsync(String resourceType) {
		DaemonToken token = this.daemonToken;
		if (token == null) {
			log.warn("Daemon token unavailable; cannot launch type re-sync for " + resourceType
			        + " (BootstrapService.resyncType(String) can still be called programmatically)");
			return false;
		}
		daemonExecutor.execute(() -> {
			try {
				log.info("Type re-sync starting for " + resourceType);
				Context.getService(BootstrapService.class).resyncType(resourceType);
				log.info("Type re-sync completed for " + resourceType);
			}
			catch (RuntimeException e) {
				// Fire-and-forget on a daemon thread — no caller upstack to surface to, so log here
				// (mirrors the after-commit sync path's log-and-swallow per ADR Decision 12).
				SafeLog.error(BootstrapLauncher.class, "Type re-sync failed for " + resourceType, e);
			}
		}, token);
		return true;
	}

	/**
	 * Runs one patient's warm ({@code work}, typically {@link BootstrapService#ensureIndexed}) on a
	 * daemon thread, returning immediately. Backs the omnibar's warm-on-chart-open: the chart is
	 * projected while the user reads the banner, so the first keystroke doesn't pay the cold
	 * projection. {@code work} signals failure by throwing; the patient then reads as failed
	 * ({@link #hasFailed}) until a later warm for it succeeds.
	 *
	 * <p>Idempotent per patient: a request for a patient already in flight is a no-op that reports
	 * success. Bounded across patients by the projection slots it shares with synchronous projections
	 * ({@link #runWithProjectionSlot}): with none free it starts nothing.
	 *
	 * @return {@code true} once a warm for this patient is in flight (newly launched or already
	 *         running); {@code false} when no {@link DaemonToken} is wired yet or no projection slot is
	 *         free, in which case nothing runs and the caller may retry later.
	 */
	public boolean launchPatientWarm(String patientUuid, Runnable work) {
		DaemonToken token = this.daemonToken;
		if (token == null || patientUuid == null) {
			return false;
		}
		// Slot first, then the in-flight entry, so a patient is only ever in warmingPatients while its
		// warm holds a slot: registering first let a racing duplicate report "in flight" for a warm
		// that then found no slot and never ran.
		Semaphore slots = this.projectionSlots;
		if (!slots.tryAcquire()) {
			log.debug("No projection slot free; not launching a warm");
			return warmingPatients.contains(patientUuid);
		}
		if (!warmingPatients.add(patientUuid)) {
			slots.release();
			log.debug("Patient warm already in flight; ignoring duplicate");
			return true;
		}
		log.debug("Patient warm starting (" + warmingPatients.size() + " in flight)");
		try {
			daemonExecutor.execute(() -> {
				try {
					work.run();
					failedPatients.remove(patientUuid);
				}
				catch (RuntimeException e) {
					// Fire-and-forget: no caller upstack, so the failure is kept for the status read.
					failedPatients.add(patientUuid);
					SafeLog.warn(BootstrapLauncher.class, "Background patient warm failed (patient uuid at DEBUG)", e);
					log.debug("Background warm failed for patient " + patientUuid);
				}
				finally {
					slots.release();
					warmingPatients.remove(patientUuid);
				}
			}, token);
		}
		catch (RuntimeException e) {
			slots.release();
			warmingPatients.remove(patientUuid);
			throw e;
		}
		return true;
	}

	/**
	 * True while a projection for this patient is running: a {@link #launchPatientWarm}, or a
	 * synchronous one through {@link #runWithProjectionSlot}.
	 */
	public boolean isWarming(String patientUuid) {
		return patientUuid != null && warmingPatients.contains(patientUuid);
	}

	/**
	 * True when this patient's last {@link #launchPatientWarm} failed and none has succeeded since.
	 * Per JVM and not persisted: a restart forgets it, as does the bound on how many are remembered.
	 */
	public boolean hasFailed(String patientUuid) {
		return patientUuid != null && failedPatients.contains(patientUuid);
	}

	/**
	 * Test seam over {@link Daemon#runInDaemonThread}: the static call cannot be stubbed, so the
	 * launch handoff (task + token) is verified through this indirection. Mirrors
	 * {@link org.openmrs.module.querystore.sync.AfterCommitDispatcher}'s executor seam.
	 */
	interface DaemonExecutor {

		void execute(Runnable task, DaemonToken token);
	}

	/** Package-private — tests live alongside the implementation; production uses the default. */
	void setDaemonExecutor(DaemonExecutor executor) {
		this.daemonExecutor = executor;
	}
}
