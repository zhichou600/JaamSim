/*
 * JaamSim Discrete Event Simulation
 * Copyright (C) 2002-2014 Ausenco Engineering Canada Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package com.jaamsim.events;

import java.util.ArrayList;

/**
 * Process is a subclass of Thread that can be managed by the discrete event
 * simulation.
 *
 * This is the basis for all functionality required by startProcess and the
 * discrete event model. Each process creates its own thread to run in. These
 * threads are managed by the eventManager and when a Process has completed
 * running is pooled for reuse.
 *
 * LOCKING: All state in the Process must be updated from a synchronized block
 * using the Process itself as the lock object. Care must be taken to never take
 * the eventManager's lock while holding the Process's lock as this can cause a
 * deadlock with other threads trying to wake you from the threadPool.
 */
public final class Process extends Thread {
	// Properties required to manage the pool of available Processes
	private static final ArrayList<Process> pool; // storage for all available Processes
	private static final int maxPoolSize = 100; // Maximum number of Processes allowed to be pooled at a given time
	private static int numProcesses = 0; // Total of all created processes to date (used to name new Processes)

	private static double timeScale; // the scale from discrete to continuous time
	private static double secondsPerTick; // The reciprocal of ticksPerSecond

	private ProcessTarget target; // The entity whose method is to be executed

	private EventManager eventManager; // The EventManager that is currently managing this Process
	private Process nextProcess; // The Process from which the present process was created

	private int flags;  // Present execution state of the process
	static final int TERMINATE = 0x01;  // The process should terminate immediately
	static final int ACTIVE = 0x02;     // The process is currently executing code
	static final int COND_WAIT = 0x04;  // The process is waiting for a condition to be satisfied
	static final int SCHED_WAIT = 0x08; // The process is waiting until a future simulation time
	// Note: The ACTIVE, COND_WAIT, and SCED_WAIT flags are mutually exclusive.
	// The TERMINATE flag can only be set at the same time as COND_WAIT or a
	// SCHED_WAIT flag.

	// Initialize the storage for the pooled Processes
	static {
		pool = new ArrayList<Process>(maxPoolSize);
	}

	private Process(String name) {
		// Construct a thread with the given name
		super(name);
		// Initialize the state flags
		flags = 0;
	}

	/**
	 * Returns the currently executing Process.
	 */
	public static final Process current() {
		Thread cur = Thread.currentThread();
		if (cur instanceof Process)
			return (Process)cur;

		throw new ProcessError("Non-process thread called Process.current()");
	}

	public static final boolean isModelProcess() {
		return (Thread.currentThread() instanceof Process);
	}

	public static final long currentTick() {
		return Process.current().eventManager.currentTick();
	}

	/**
	 * Run method invokes the method on the target with the given arguments.
	 * A process loops endlessly after it is created executing the method on the
	 * target set as the entry point.  After completion, it calls endProcess and
	 * will return it to a process pool if space is available, otherwise the resources
	 * including the backing thread will be released.
	 *
	 * This method is called by Process.getProcess()
	 */
	@Override
	public void run() {
		while (true) {
			synchronized (pool) {
				// Add ourselves to the pool and wait to be assigned work
				pool.add(this);
				// Set the present process to sleep, and release its lock
				// (done by pool.wait();)
				// Note: the try/while(true)/catch construct is needed to avoid
				// spurious wake ups allowed as of Java 5.  All legitimate wake
				// ups are done through the InterruptedException.
				try {
					while (true) { pool.wait(); }
				} catch (InterruptedException e) {}
			}

			// Process has been woken up, execute the method we have been assigned
			this.execute();
		}
	}

	private void execute() {
		ProcessTarget procTarget;

		// Save a locally-consistent processTarget, synchronized
		// against against a call to allocate() from a separate thread
		synchronized (this) {
			procTarget = this.target;
			this.target = null;
		}

		try {
			// Execute the method
			procTarget.process();

			// Notify the event manager that the process has been completed
			synchronized (this) {
				eventManager.releaseProcess();
				eventManager = null;
			}
			return;
		}
		catch (ThreadKilledException e) {
			// If the process was killed by a terminateThread method then
			// return to the beginning of the process loop
			this.clearFlag(ACTIVE);
			this.clearFlag(TERMINATE);
			eventManager = null;
			return;
		}
		catch (Throwable e) {
			eventManager.handleProcessError(e);
		}
	}

	// Set up a new process for the given entity, method, and arguments
	// Called from Process.start() and from EventManager.startExternalProcess()
	static Process allocate(EventManager eventManager, ProcessTarget proc) {

		// Create the new process
		Process newProcess = Process.getProcess();

		// Setup the process state for execution
		synchronized (newProcess) {
			newProcess.target = proc;
			newProcess.eventManager = eventManager;
			newProcess.flags = 0;
		}

		return newProcess;
	}

	// Return a process from the pool or create a new one
	private static Process getProcess() {
		while (true) {
			synchronized (pool) {
				// If there is an available process in the pool, then use it
				if (pool.size() > 0) {
					return pool.remove(pool.size() - 1);
				}
				// If there are no process in the pool, then create a new one and add it to the pool
				else {
					numProcesses++;
					Process temp = new Process("processthread-" + numProcesses);
					temp.start(); // Note: Thread.start() calls Process.run which adds the new process to the pool
				}
			}

			// Allow the Process.run method to execute so that it can add the
			// new process to the pool
			// Note: that the lock on the pool has been dropped, so that the
			// Process.run method can grab it.
			try { Thread.sleep(10); } catch (InterruptedException e) {}
		}
	}

	synchronized void setNextProcess(Process next) {
		nextProcess = next;
	}

	synchronized Process getNextProcess() {
		return nextProcess;
	}

	synchronized void wake(EventManager eventManager) {
		this.setFlag(Process.ACTIVE);
		this.eventManager = eventManager;
		if (this.testFlag(Process.TERMINATE))
			throw new ThreadKilledException("Thread killed");
	}

	synchronized void setFlag(int flag) {
		flags |= flag;
	}

	synchronized void clearFlag(int flag) {
		flags &= ~flag;
	}

	synchronized boolean testFlag(int flag) {
		return (flags & flag) != 0;
	}

	static void setSimTimeScale(double scale) {
		timeScale = scale;
		secondsPerTick = 3600.0d / scale;
	}

	/**
	 * Return the number of seconds represented by the given number of ticks.
	 */
	public static final double ticksToSeconds(long ticks) {
		return ticks * secondsPerTick;
	}

	public static double getSimTimeFactor() {
		return timeScale;
	}

	public static double getEventTolerance() {
		return (1.0d / getSimTimeFactor());
	}
}
