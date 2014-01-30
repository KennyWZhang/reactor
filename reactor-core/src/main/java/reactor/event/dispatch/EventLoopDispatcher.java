/*
 * Copyright (c) 2011-2013 GoPivotal, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.event.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.alloc.Reference;
import reactor.event.Event;
import reactor.support.NamedDaemonThreadFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link Dispatcher} that uses a {@link BlockingQueue} to queue tasks to be executed.
 *
 * @author Jon Brisbin
 * @author Stephane Maldini
 * @author Andy Wilkinson
 */
public final class EventLoopDispatcher extends AbstractReferenceCountingDispatcher {

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final ExecutorService                 taskExecutor;
	private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;

	/**
	 * Creates a new {@literal EventLoopDispatcher} with the given {@literal name} and {@literal backlog}.
	 *
	 * @param name
	 * 		The name
	 * @param backlog
	 * 		The backlog size
	 */
	public EventLoopDispatcher(String name, int backlog) {
		this(name, backlog, null);
	}

	/**
	 * Creates a new {@literal EventLoopDispatcher} with the given {@literal name} and {@literal backlog}.
	 *
	 * @param name
	 * 		The name
	 * @param backlog
	 * 		The backlog size
	 * @param uncaughtExceptionHandler
	 * 		The {@code UncaughtExceptionHandler}
	 */
	public EventLoopDispatcher(final String name,
	                           int backlog,
	                           final Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
		super(backlog);
		this.uncaughtExceptionHandler = uncaughtExceptionHandler;
		this.taskExecutor = Executors.newSingleThreadExecutor(new NamedDaemonThreadFactory(name, getContext()));
		this.taskExecutor.submit(new TaskExecutingRunnable());
	}

	@Override
	public boolean awaitAndShutdown(long timeout, TimeUnit timeUnit) {
		shutdown();
		try {
			taskExecutor.awaitTermination(timeout, timeUnit);
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		return true;
	}

	@Override
	public void shutdown() {
		taskExecutor.shutdown();
		super.shutdown();
	}

	@Override
	public void halt() {
		taskExecutor.shutdownNow();
		super.halt();
	}

	@Override
	protected Task createTask() {
		return new EventLoopTask();
	}

	private class EventLoopTask extends SingleThreadTask<Event<?>> {
		@Override
		protected void execute() {
			try {
				route(eventRouter,
				      key,
				      event,
				      (null != consumerRegistry ? consumerRegistry.select(key) : null),
				      completionConsumer,
				      errorConsumer);
			} finally {
				if(null != getRef() && getRef().getReferenceCount() == 1) {
					getRef().release();
				}
			}
		}
	}

	private class TaskExecutingRunnable implements Runnable {
		@Override
		public void run() {
			Reference<Task<Event<?>>> ref;
			for(; ; ) {
				try {
					while(null != (ref = getTaskQueue().poll(1, TimeUnit.SECONDS))) {
						try {
							ref.get().execute();
						} catch(Throwable t) {
							if(null != uncaughtExceptionHandler) {
								uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), t);
							}
							if(log.isErrorEnabled()) {
								log.error(t.getMessage(), t);
							}
						}
					}
				} catch(InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
	}

}
