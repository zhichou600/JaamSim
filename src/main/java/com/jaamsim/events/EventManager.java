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
import java.util.Arrays;

/**
 * Class EventManager - Sandwell Discrete Event Simulation
 * <p>
 * The EventManager is responsible for scheduling future events, controlling
 * conditional event evaluation, and advancing the simulation time. Events are
 * scheduled in based on:
 * <ul>
 * <li>1 - The execution time scheduled for the event
 * <li>2 - The priority of the event (if scheduled to occur at the same time)
 * <li>3 - If both 1) and 2) are equal, the order in which the event was
 * scheduled (FILO - Stack ordering)
 * </ul>
 * <p>
 * The event time is scheduled using a backing long value. Double valued time is
 * taken in by the scheduleWait function and scaled to the nearest long value
 * using the simTimeFactor.
 * <p>
 * The EventManager thread is always the bottom thread on the threadStack, so
 * that after each event has finished, along with any spawned events, the
 * program control will pass back to the EventManager.
 * <p>
 * The runnable interface is implemented so that the eventManager runs as a
 * separate thread.
 * <p>
 * EventManager is held as a static member of class entity, this ensures that
 * all entities will schedule themselves with the same event manager.
 */
public final class EventManager implements Runnable {
	public final String name;

	private final Object lockObject; // Object used as global lock for synchronization
	private Event[] eventList;
	private int headEvtIdx;

	private boolean executeEvents;

	private final ArrayList<Process> conditionalList; // List of all conditionally waiting processes
	private final Thread eventManagerThread;

	private long currentTick; // Master simulation time (long)
	private long nextTick; // The next tick to execute events at
	private long targetTick; // the largest time we will execute events for (run to time)

	private double ticksPerSecond; // The number of discrete ticks per simulated second

	// Real time execution state
	private long realTimeTick;    // the simulation tick corresponding to the wall-clock millis value
	private long realTimeMillis;  // the wall-clock time in millis

	private boolean executeRealTime;  // TRUE if the simulation is to be executed in Real Time mode
	private boolean rebaseRealTime;   // TRUE if the time keeping for Real Time model needs re-basing
	private int realTimeFactor;       // target ratio of elapsed simulation time to elapsed wall clock time

	private EventTimeListener timelistener;
	private EventErrorListener errListener;
	private EventTraceListener trcListener;

	/**
	 * Allocates a new EventManager with the given parent and name
	 *
	 * @param parent the connection point for this EventManager in the tree
	 * @param name the name this EventManager should use
	 */
	private EventManager(String name) {
		// Basic initialization
		this.name = name;
		lockObject = new Object();

		// Initialize the thread which processes events from this EventManager
		eventManagerThread = new Thread(this, "evt-" + name);

		// Initialize and event lists and timekeeping variables
		currentTick = 0;
		nextTick = 0;

		ticksPerSecond = 1000000.0d;

		eventList = new Event[10000];
		headEvtIdx = -1;
		conditionalList = new ArrayList<Process>();

		executeEvents = false;
		executeRealTime = false;
		realTimeFactor = 1;
		rebaseRealTime = true;
		setTimeListener(null);
		setErrorListener(null);
	}

	// Used to handshake with the calling thread and make sure the evt thread
	// has made it to the first wait state
	private static class InitListener implements EventTimeListener {
		final Thread waitThread;

		InitListener() {
			waitThread = Thread.currentThread();
		}

		@Override
		public void tickUpdate(long tick) {}
		@Override
		public void timeRunning(boolean running) {
			synchronized (this) {
				waitThread.interrupt();
			}
		}
	}

	public static EventManager initEventManager(String name) {
		EventManager evtman = new EventManager(name);
		InitListener e = new InitListener();
		synchronized (e) {
			evtman.setTimeListener(e);
			evtman.eventManagerThread.start();
			try {
				e.wait();
			}
			catch (InterruptedException e2) {}
			evtman.setTimeListener(null);
		}
		return evtman;
	}

	public final void setTimeListener(EventTimeListener l) {
		synchronized (lockObject) {
			if (l != null)
				timelistener = l;
			else
				timelistener = new DefaultTimeListener();

			timelistener.tickUpdate(currentTick);
		}
	}

	public final void setErrorListener(EventErrorListener l) {
		synchronized (lockObject) {
			if (l != null)
				errListener = l;
			else
				errListener = new DefaultErrorListener();
		}
	}

	public final void setTraceListener(EventTraceListener l) {
		synchronized (lockObject) {
			trcListener = l;
		}
	}

	public void clear() {
		synchronized (lockObject) {
			currentTick = 0;
			nextTick = 0;
			targetTick = Long.MAX_VALUE;
			timelistener.tickUpdate(currentTick);
			rebaseRealTime = true;

			// Kill threads on the event stack
			for (int i = 0; i <= headEvtIdx; i++) {
				Process proc = eventList[i].target.getProcess();
				if (proc == null)
					continue;

				if (proc.testFlag(Process.ACTIVE)) {
					throw new ProcessError("EVT:%s - Cannot terminate an active thread", name);
				}

				proc.setFlag(Process.TERMINATE);
				proc.interrupt();
			}
			Arrays.fill(eventList, null);
			headEvtIdx = -1;

			// Kill conditional threads
			for (Process each : conditionalList) {
				if (each.testFlag(Process.ACTIVE)) {
					throw new ProcessError("EVT:%s - Cannot terminate an active thread", name);
				}

				each.setFlag(Process.TERMINATE);
				each.interrupt();
			}
			conditionalList.clear();
		}
	}

	/**
	 * Main event processing loop for the eventManager.
	 *
	 * Each eventManager runs its loop continuous throughout the simulation run
	 * with its own thread. The loop for each eventManager is never terminated.
	 * It is only paused and restarted as required. The run method is called by
	 * eventManager.start().
	 */
	@Override
	public void run() {
		synchronized (lockObject) {
			// Loop continuously
			while (true) {
				if (headEvtIdx == -1 ||
				    eventList[headEvtIdx].schedTick >= targetTick) {
					executeEvents = false;
				}

				if (!executeEvents) {
					timelistener.timeRunning(false);
					this.threadWait();
					timelistener.timeRunning(true);
					continue;
				}

				// If the next event is at the current tick, execute it
				if (eventList[headEvtIdx].schedTick == currentTick) {
					// Remove the event from the future events
					Event nextEvent = eventList[headEvtIdx];
					eventList[headEvtIdx] = null;
					headEvtIdx--;

					if (trcListener != null) trcListener.traceEvent(this, nextEvent);
					Process p = nextEvent.target.getProcess();
					if (p == null)
						p = Process.allocate(this, nextEvent.target);
					// Pass control to this event's thread
					p.setNextProcess(null);
					switchThread(p);
					continue;
				}

				// If the next event would require us to advance the time, check the
				// conditonal events
				if (eventList[headEvtIdx].schedTick > nextTick) {
					if (conditionalList.size() > 0) {
						// Loop through the conditions in reverse order and add to the linked
						// list of active threads
						for (int i = 0; i < conditionalList.size() - 1; i++) {
							conditionalList.get(i).setNextProcess(conditionalList.get(i + 1));
						}
						conditionalList.get(conditionalList.size() - 1).setNextProcess(null);

						// Wake up the first conditional thread to be tested
						// at this point, nextThread == conditionalList.get(0)
						switchThread(conditionalList.get(0));
					}

					// If a conditional event was satisfied, we will have a new event at the
					// beginning of the eventStack for the current tick, go back to the
					// beginning, otherwise fall through to the time-advance
					nextTick = eventList[headEvtIdx].schedTick;
					if (nextTick == currentTick)
						continue;
				}

				// Advance to the next event time
				if (executeRealTime) {
					// Loop until the next event time is reached
					long realTick = this.calcRealTimeTick();
					if (realTick < nextTick) {
						// Update the displayed simulation time
						currentTick = realTick;
						timelistener.tickUpdate(currentTick);
						//Halt the thread for 20ms and then reevaluate the loop
						try { lockObject.wait(20); } catch( InterruptedException e ) {}
						continue;
					}
				}

				// advance time
				currentTick = nextTick;
				timelistener.tickUpdate(currentTick);
			}
		}
	}

	/**
	 * Return the simulation time corresponding the given wall clock time
	 * @param simTime = the current simulation time used when setting a real-time basis
	 * @return simulation time in seconds
	 */
	private long calcRealTimeTick() {
		long curMS = System.currentTimeMillis();
		if (rebaseRealTime) {
			realTimeTick = currentTick;
			realTimeMillis = curMS;
			rebaseRealTime = false;
		}

		double simElapsedsec = ((curMS - realTimeMillis) * realTimeFactor) / 1000.0d;
		long simElapsedTicks = secondsToNearestTick(simElapsedsec);
		return realTimeTick + simElapsedTicks;
	}

	/**
	 * Called when a process has finished invoking a model method and unwinds
	 * the threadStack one level.
	 */
	void releaseProcess() {
		synchronized (lockObject) {
			assertNotWaitUntil();
			if (trcListener != null) trcListener.traceProcessEnd(this);
			Process cur = Process.current();
			Process next = cur.getNextProcess();
			cur.setNextProcess(null);
			cur.clearFlag(Process.ACTIVE);

			if (next != null) {
				next.interrupt();
			} else {
				// TODO: check for the switching of eventmanagers
				eventManagerThread.interrupt();
			}
		}
	}

	/**
	// Pause the current active thread and restart the next thread on the
	// active thread list. For this case, a future event or conditional event
	// has been created for the current thread.  Called by
	// eventManager.scheduleWait() and related methods, and by
	// eventManager.waitUntil().
	// restorePreviousActiveThread()
	 * Must hold the lockObject when calling this method.
	 */
	private void popProcess() {
		Process cur = Process.current();
		Process next = cur.getNextProcess();

		cur.clearFlag(Process.ACTIVE);
		cur.setNextProcess(null);
		if (next != null)
			switchThread(next);
		else
			switchThread(eventManagerThread);
		cur.wake(this);
	}

	/**
	 * Push another thread onto the Process stack and wait for it to complete
	 * Must hold the lockObject when calling this method.
	 * @param next
	 */
	private void pushProcess(Process next) {
		next.setNextProcess(Process.current());
		next.interrupt();
		threadWait();
	}

	/**
	 * Must hold the lockObject when calling this method
	 * @param next
	 */
	private void switchThread(Thread next) {
		next.interrupt();
		threadWait();
	}

	/**
	 * Calculate the time for an event taking into account numeric overflow.
	 * Must hold the lockObject when calling this method
	 */
	private long calculateEventTime(long waitLength) {
		// Test for negative duration schedule wait length
		if(waitLength < 0)
			throw new ProcessError("Negative duration wait is invalid (wait length specified to be %d )", waitLength);

		// Check for numeric overflow of internal time
		long nextEventTime = currentTick + waitLength;
		if (nextEventTime < 0)
			nextEventTime = Long.MAX_VALUE;

		return nextEventTime;
	}

	public void scheduleSingleProcess(long waitLength, int eventPriority, boolean fifo, ProcessTarget t) {
		assertNotWaitUntil();
		synchronized (lockObject) {
			long eventTime = calculateEventTime(waitLength);
			for (int i = headEvtIdx; i >= 0; i--) {
				Event each = eventList[i];
				// We passed where any duplicate could be, break out to the
				// insertion part
				if (each.schedTick > eventTime)
					break;

				// if we have an exact match, do not schedule another event
				if (each.schedTick == eventTime &&
				    each.priority == eventPriority &&
				    each.target == t) {
					if (trcListener != null) trcListener.traceSchedProcess(this, each);
					return;
				}
			}

			// Create an event for the new process at the present time, and place it on the event stack
			Event newEvent = new Event(currentTick, eventTime, eventPriority, t);
			if (trcListener != null) trcListener.traceSchedProcess(this, newEvent);
			addEventToStack(newEvent, fifo);
		}
	}

	/**
	 * Schedules a future event to occur with a given priority.  Lower priority
	 * events will be executed preferentially over higher priority.  This is
	 * by lower priority events being placed higher on the event stack.
	 * @param ticks the number of discrete ticks from now to schedule the event.
	 * @param priority the priority of the scheduled event: 1 is the highest priority (default is priority 5)
	 */
	public void waitTicks(long ticks, int priority, boolean fifo) {
		assertNotWaitUntil();
		synchronized (lockObject) {
			long nextEventTime = calculateEventTime(ticks);
			WaitTarget t = new WaitTarget(Process.current());
			Event temp = new Event(currentTick, nextEventTime, priority, t);
			if (trcListener != null) trcListener.traceWait(this, temp);
			addEventToStack(temp, fifo);
			popProcess();
		}
	}

	/**
	 * Adds a new event to the event stack.  This method will add an event to
	 * the event stack based on its scheduled time, priority, and in stack
	 * order for equal time/priority.
	 *
	 * Must hold the lockObject when calling this method.
	 */
	private void addEventToStack(Event newEvent, boolean fifo) {
		int lowIdx = 0;
		int highIdx = headEvtIdx;

		while (lowIdx <= highIdx) {
			int testIdx = (lowIdx + highIdx) >>> 1; // use unsigned shift to avoid overflow

			// Compare events by scheduled time first
			if (eventList[testIdx].schedTick < newEvent.schedTick) {
				highIdx = testIdx - 1;
				continue;
			}

			if (eventList[testIdx].schedTick > newEvent.schedTick) {
				lowIdx = testIdx + 1;
				continue;
			}

			// events at the same time use priority as a tie-breaker
			if (eventList[testIdx].priority < newEvent.priority) {
				highIdx = testIdx - 1;
				continue;
			}

			if (eventList[testIdx].priority > newEvent.priority) {
				lowIdx = testIdx + 1;
				continue;
			}

			// Events at equal time and priority are done in fifo or lifo order
			// depending on the passed in policy
			if (fifo)
				highIdx = testIdx - 1;
			else
				lowIdx = testIdx + 1;
		}

		// Expand the eventList by doubling the size
		if (eventList.length - 1 == headEvtIdx) {
			eventList = Arrays.copyOf(eventList, eventList.length * 2);
		}

		// Insert the event in the stack, only copy array elements if not prepending
		if (lowIdx <= headEvtIdx)
			System.arraycopy(eventList, lowIdx, eventList, lowIdx + 1, (headEvtIdx - lowIdx + 1));

		eventList[lowIdx] = newEvent;
		headEvtIdx++;
	}

	/**
	 * Debugging aid to test that we are not executing a conditional event, useful
	 * to try and catch places where a waitUntil was missing a waitUntilEnded.
	 * While not fatal, it will print out a stack dump to try and find where the
	 * waitUntilEnded was missed.
	 */
	private void assertNotWaitUntil() {
		Process process = Process.current();
		if (process.testFlag(Process.COND_WAIT)) {
			System.out.println("AUDIT - waitUntil without waitUntilEnded " + process);
			for (StackTraceElement elem : process.getStackTrace()) {
				System.out.println(elem.toString());
			}
		}
	}

	/**
	 * Used to achieve conditional waits in the simulation.  Adds the calling
	 * thread to the conditional stack, then wakes the next waiting thread on
	 * the thread stack.
	 */
	public void waitUntil() {
		synchronized (lockObject) {
			if (!conditionalList.contains(Process.current())) {
				if (trcListener != null) trcListener.traceWaitUntil(this);
				Process.current().setFlag(Process.COND_WAIT);
				conditionalList.add(Process.current());
			}
			popProcess();
		}
	}

	public void waitUntilEnded() {
		synchronized (lockObject) {
			// Do not wait at all if we never actually were on the waitUntilStack
			// ie. we never called waitUntil
			if (!conditionalList.remove(Process.current()))
				return;

			Process cur = Process.current();
//			if (!cur.testFlag(Process.COND_WAIT)) {
//				System.out.println("ERROR - waitUntil without waitUntilEnded " + cur);
//				for (StackTraceElement elem : cur.getStackTrace()) {
//					System.out.println(elem.toString());
//				}
//			}

			cur.clearFlag(Process.COND_WAIT);
			WaitTarget t = new WaitTarget(cur);
			Event temp = new Event(currentTick, currentTick, 0, t);
			if (trcListener != null) trcListener.traceWaitUntilEnded(this, temp);
			addEventToStack(temp, true);
			popProcess();
		}
	}

	public void start(ProcessTarget t) {
		Process newProcess = Process.allocate(this, t);
		// Notify the eventManager that a new process has been started
		synchronized (lockObject) {
			if (trcListener != null) trcListener.traceProcessStart(this, t);
			// Transfer control to the new process
			pushProcess(newProcess);
		}
	}

	/**
	 * Remove an event from the eventList, must hold the lockObject.
	 * @param idx
	 * @return
	 */
	private Event removeEvent(int idx) {
		Event e = eventList[idx];
		System.arraycopy(eventList, idx + 1, eventList, idx, headEvtIdx - idx);
		eventList[headEvtIdx] = null;
		headEvtIdx--;
		return e;
	}
	/**
	 *	Removes the thread from the pending list and executes it immediately
	 */
	public void interrupt( Process intThread ) {
		synchronized (lockObject) {
			if (intThread.testFlag(Process.ACTIVE)) {
				throw new ProcessError("EVT:%s - Cannot interrupt an active thread", name);
			}

			assertNotWaitUntil();

			for (int i = headEvtIdx; i >= 0; i--) {
				if (eventList[i].target.getProcess() == intThread) {
					Event interruptEvent = removeEvent(i);
					Process proc = interruptEvent.target.getProcess();
					if (trcListener != null) trcListener.traceInterrupt(this, interruptEvent);
					pushProcess(proc);
					return;
				}
			}
			throw new ProcessError("EVT:%s - Tried to interrupt a Process that couldn't be found in event list", name);
		}
	}

	/**
	 *	Removes an event from the pending list and executes it immediately.
	 */
	public void interrupt(ProcessTarget t) {
		synchronized (lockObject) {
			assertNotWaitUntil();

			for (int i = headEvtIdx; i >= 0; i--) {
				if (eventList[i].target == t) {
					Event interruptEvent = removeEvent(i);
					if (trcListener != null) trcListener.traceInterrupt(this, interruptEvent);
					Process proc = Process.allocate(this, interruptEvent.target);
					pushProcess(proc);
					return;
				}
			}
			throw new ProcessError("EVT:%s - Tried to interrupt a ProcessTarget that couldn't be found in event list", name);
		}
	}

	public void terminateThread( Process killThread ) {
		synchronized (lockObject) {
			if (killThread.testFlag(Process.ACTIVE)) {
				throw new ProcessError("EVT:%s - Cannot terminate an active thread", name);
			}

			assertNotWaitUntil();

			if (conditionalList.remove(killThread)) {
				killThread.setFlag(Process.TERMINATE);
				killThread.interrupt();
				return;
			}

			for (int i = headEvtIdx; i >= 0; i--) {
				if (eventList[i].target.getProcess() == killThread) {
					Event temp = removeEvent(i);
					if (trcListener != null) trcListener.traceKill(this, temp);
					killThread.setFlag(Process.TERMINATE);
					killThread.interrupt();
					return;
				}
			}
		}
		throw new ProcessError("EVT:%s - Tried to terminate a Process that couldn't be found in event list", name);
	}

	/**
	 *	Removes an event from the pending list and executes it immediately.
	 */
	public void terminate(ProcessTarget t) {
		synchronized (lockObject) {
			assertNotWaitUntil();

			for (int i = headEvtIdx; i >= 0; i--) {
				if (eventList[i].target == t) {
					Event temp = removeEvent(i);
					if (trcListener != null) trcListener.traceKill(this, temp);
					return;
				}
			}
		}
		throw new ProcessError("EVT:%s - Tried to terminate a ProcessTarget that couldn't be found in event list", name);
	}

	public long currentTick() {
		synchronized (lockObject) {
			return currentTick;
		}
	}

	public void setExecuteRealTime(boolean useRealTime, int factor) {
		synchronized (lockObject) {
			executeRealTime = useRealTime;
			realTimeFactor = factor;
			if (useRealTime)
				rebaseRealTime = true;
		}
	}

	/**
	 * Locks the calling thread in an inactive state to the global lock.
	 * When a new thread is created, and the current thread has been pushed
	 * onto the inactive thread stack it must be put to sleep to preserve
	 * program ordering.
	 * <p>
	 * The function takes no parameters, it puts the calling thread to sleep.
	 * This method is NOT static as it requires the use of wait() which cannot
	 * be called from a static context
	 * <p>
	 * There is a synchronized block of code that will acquire the global lock
	 * and then wait() the current thread.
	 */
	private void threadWait() {
		// Ensure that the thread owns the global thread lock
		try {
			/*
			 * Halt the thread and only wake up by being interrupted.
			 *
			 * The infinite loop is _absolutely_ necessary to prevent
			 * spurious wakeups from waking us early....which causes the
			 * model to get into an inconsistent state causing crashes.
			 */
			while (true) { lockObject.wait(); }
		}
		// Catch the exception when the thread is interrupted
		catch( InterruptedException e ) {}

	}

	public void scheduleProcess(long waitLength, int eventPriority, boolean fifo, ProcessTarget t) {
		synchronized (lockObject) {
			long schedTick = calculateEventTime(waitLength);
			Event e = new Event(currentTick, schedTick, eventPriority, t);
			if (trcListener != null) trcListener.traceSchedProcess(this, e);
			addEventToStack(e, fifo);
		}
	}

	/**
	 * Sets the value that is tested in the doProcess loop to determine if the
	 * next event should be executed.  If set to false, the eventManager will
	 * execute a threadWait() and wait until an interrupt is generated.  It is
	 * guaranteed in this state that there is an empty thread stack and the
	 * thread referenced in activeThread is the eventManager thread.
	 */
	public void pause() {
		synchronized (lockObject) {
			executeEvents = false;
		}
	}

	/**
	 * Sets the value that is tested in the doProcess loop to determine if the
	 * next event should be executed.  Generates an interrupt of activeThread
	 * in case the eventManager thread has already been paused and needs to
	 * resume the event execution loop.  This prevents the model being resumed
	 * from an inconsistent state.
	 */
	public void resume(long targetTicks) {
		synchronized (lockObject) {
			targetTick = targetTicks;
			rebaseRealTime = true;
			if (executeEvents)
				return;

			executeEvents = true;
			eventManagerThread.interrupt();
		}
	}

	@Override
	public String toString() {
		return name;
	}


	public final void setSimTimeScale(double scale) {
		ticksPerSecond = scale / 3600.0d;
		Process.setSimTimeScale(scale);
	}

	/**
	 * Convert the number of seconds rounded to the nearest tick.
	 */
	public final long secondsToNearestTick(double seconds) {
		return Math.round(seconds * ticksPerSecond);
	}

	void handleProcessError(Throwable t) {
		this.pause();
		synchronized (lockObject) {
			errListener.handleError(this, t, currentTick);
		}
	}

	private static class DefaultTimeListener implements EventTimeListener {
		@Override
		public void tickUpdate(long tick) {}
		@Override
		public void timeRunning(boolean running) {}
	}

	private static class DefaultErrorListener implements EventErrorListener {
		@Override
		public void handleError(EventManager evt, Throwable t, long currentTick) {}
	}
}
