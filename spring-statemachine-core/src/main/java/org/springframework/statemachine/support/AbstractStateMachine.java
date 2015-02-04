/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.statemachine.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.OrderComparator;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.annotation.OnTransition;
import org.springframework.statemachine.listener.CompositeStateMachineListener;
import org.springframework.statemachine.listener.StateMachineListener;
import org.springframework.statemachine.processor.StateMachineHandler;
import org.springframework.statemachine.processor.StateMachineOnTransitionHandler;
import org.springframework.statemachine.processor.StateMachineRuntime;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.transition.Transition;
import org.springframework.statemachine.transition.TransitionKind;
import org.springframework.statemachine.trigger.Trigger;
import org.springframework.util.Assert;

/**
 * Base implementation of a {@link StateMachine} loosely modelled from UML state
 * machine.
 * 
 * @author Janne Valkealahti
 *
 * @param <S> the type of state
 * @param <E> the type of event
 */
public abstract class AbstractStateMachine<S, E> extends LifecycleObjectSupport implements StateMachine<State<S,E>, E> {

	private static final Log log = LogFactory.getLog(AbstractStateMachine.class);

	private final Collection<State<S,E>> states;

	private final Collection<Transition<S,E>> transitions;

	private final State<S,E> initialState;

	private final ExtendedState extendedState;

	private final Queue<Message<E>> eventQueue = new ConcurrentLinkedQueue<Message<E>>();

	private final LinkedList<Message<E>> deferList = new LinkedList<Message<E>>();

	private final CompositeStateMachineListener<S, E> stateListener = new CompositeStateMachineListener<S, E>();

	private volatile State<S,E> currentState;
	
	private volatile Runnable task;

	/**
	 * Instantiates a new abstract state machine.
	 *
	 * @param states the states of this machine
	 * @param transitions the transitions of this machine
	 * @param initialState the initial state of this machine
	 */
	public AbstractStateMachine(Collection<State<S, E>> states, Collection<Transition<S, E>> transitions,
			State<S, E> initialState) {
		this(states, transitions, initialState, new DefaultExtendedState());
	}
	
	/**
	 * Instantiates a new abstract state machine.
	 *
	 * @param states the states of this machine
	 * @param transitions the transitions of this machine
	 * @param initialState the initial state of this machine
	 * @param extendedState the extended state of this machine
	 */
	public AbstractStateMachine(Collection<State<S, E>> states, Collection<Transition<S, E>> transitions,
			State<S, E> initialState, ExtendedState extendedState) {
		super();
		this.states = states;
		this.transitions = transitions;
		this.initialState = initialState;
		this.extendedState = extendedState;
	}

	@Override
	public State<S,E> getState() {
		return currentState;
	}

	@Override
	public State<S,E> getInitialState() {
		return initialState;
	}

	@Override
	public void sendEvent(Message<E> event) {
		// TODO: machine header looks weird!
		event = MessageBuilder.fromMessage(event).setHeader("machine", this).build();
		if (log.isDebugEnabled()) {
			log.debug("Queue event " + event);
		}
		eventQueue.add(event);
		scheduleEventQueueProcessing();
	}

	@Override
	public void sendEvent(E event) {
		sendEvent(MessageBuilder.withPayload(event).build());
	}

	@Override
	protected void doStart() {
		super.doStart();
		switchToState(initialState, null);
	}

	@Override
	public void addStateListener(StateMachineListener<State<S, E>, E> listener) {
		stateListener.register(listener);
	}

	/**
	 * Gets the {@link State}s defined in this machine. Returned collection is
	 * an unmodifiable copy because states in a state machine are immutable.
	 *
	 * @return immutable copy of existing states
	 */
	public Collection<State<S,E>> getStates() {
		return Collections.unmodifiableCollection(states);
	}
	
	private void switchToState(State<S,E> state, Message<E> event) {
		log.info("Moving into state=" + state + " from " + currentState);
				
		exitFromState(currentState, event);
		stateListener.stateChanged(currentState, state);
		
		callHandlers(currentState, state, event);
		
		currentState = state;
		entryToState(state, event);

		for (Transition<S,E> transition : transitions) {
			State<S,E> source = transition.getSource();
			State<S,E> target = transition.getTarget();
			if (transition.getTrigger() == null && source.equals(currentState)) {
				switchToState(target, event);
			}

		}

	}

	private void exitFromState(State<S, E> state, Message<E> event) {
		if (state != null) {
			MessageHeaders messageHeaders = event != null ? event.getHeaders() : new MessageHeaders(
					new HashMap<String, Object>());
			Collection<Action> actions = state.getExitActions();
			if (actions != null) {
				for (Action action : actions) {
					action.execute(new DefaultStateContext(messageHeaders, extendedState));
				}
			}
		}
	}

	private void entryToState(State<S,E> state, Message<E> event) {
		if (state != null) {
			MessageHeaders messageHeaders = event != null ? event.getHeaders() : new MessageHeaders(
					new HashMap<String, Object>());
			Collection<Action> actions = state.getEntryActions();
			if (actions != null) {
				for (Action action : actions) {
					action.execute(new DefaultStateContext(messageHeaders, extendedState));
				}
			}
		}
	}
	
	private void processEventQueue() {
		log.debug("Process event queue");
		Message<E> queuedEvent = null;
		while ((queuedEvent = eventQueue.poll()) != null) {
			Message<E> defer = null;
			for (Transition<S,E> transition : transitions) {
				State<S,E> source = transition.getSource();
				State<S,E> target = transition.getTarget();
				Trigger<S, E> trigger = transition.getTrigger();
				if (source.equals(currentState)) {
					if (trigger != null && trigger.evaluate(queuedEvent.getPayload())) {
						boolean transit = transition.transit(new DefaultStateContext(queuedEvent.getHeaders(), extendedState));
						if (transit && transition.getKind() != TransitionKind.INTERNAL) {
							switchToState(target, queuedEvent);
						}
						break;
					} else if (source.getDeferredEvents() != null && source.getDeferredEvents().contains(queuedEvent.getPayload())) {
						defer = queuedEvent;
					}
				}
			}
			if (defer != null) {
				log.info("Deferring event " + defer);
				deferList.addLast(defer);
			}
		}
	}

	private void processDeferList() {
		log.debug("Process defer list");
		ListIterator<Message<E>> iterator = deferList.listIterator();
		while (iterator.hasNext()) {
			Message<E> event = iterator.next();
			for (Transition<S,E> transition : transitions) {
				State<S,E> source = transition.getSource();
				State<S,E> target = transition.getTarget();
				Trigger<S, E> trigger = transition.getTrigger();
				if (source.equals(currentState)) {
					if (trigger != null && trigger.evaluate(event.getPayload())) {
						boolean transit = transition.transit(new DefaultStateContext(event.getHeaders(), extendedState));
						if (transit && transition.getKind() != TransitionKind.INTERNAL) {
							switchToState(target, event);
						}
						iterator.remove();
					}
				}
			}
		}
	}

	private void scheduleEventQueueProcessing() {
		if (task == null) {
			task = new Runnable() {
				@Override
				public void run() {
					processEventQueue();
					processDeferList();
					task = null;
				}
			};
			getTaskExecutor().execute(task);
		}
	}
	
	private void callHandlers(State<S,E> sourceState, State<S,E> targetState, Message<E> event) {
		if (sourceState != null && targetState != null) {
			MessageHeaders messageHeaders = event != null ? event.getHeaders() : new MessageHeaders(
					new HashMap<String, Object>());
			StateContext stateContext = new DefaultStateContext(messageHeaders, extendedState);
			getStateMachineHandlerResults(getStateMachineHandlers(sourceState, targetState), stateContext);
		}
		
	}
	
		
	private List<Object> getStateMachineHandlerResults(List<StateMachineHandler> stateMachineHandlers, final StateContext stateContext) {
		StateMachineRuntime runtime = new StateMachineRuntime() {			
			@Override
			public StateContext getStateContext() {
				return stateContext;
			}
		};
		List<Object> results = new ArrayList<Object>();
		for (StateMachineHandler handler : stateMachineHandlers) {
			results.add(handler.handle(runtime));
		}
		return results;
	}
	
	private List<StateMachineHandler> getStateMachineHandlers(State<S,E> sourceState, State<S,E> targetState) {
		BeanFactory beanFactory = getBeanFactory();
		
		// TODO think how to handle null bf
		if (beanFactory == null) {
			return Collections.emptyList();
		}
		Assert.state(beanFactory instanceof ListableBeanFactory, "Bean factory must be instance of ListableBeanFactory");
		Map<String, StateMachineOnTransitionHandler> handlers = ((ListableBeanFactory) beanFactory)
				.getBeansOfType(StateMachineOnTransitionHandler.class);
		List<StateMachineHandler> handlersList = new ArrayList<StateMachineHandler>();

		for (Entry<String, StateMachineOnTransitionHandler> entry : handlers.entrySet()) {
			OnTransition annotation = entry.getValue().getAnnotation();
			String source = annotation.source();
			String target = annotation.target();
			String s = sourceState.getId().toString();
			String t = targetState.getId().toString();
			if (s.equals(source) && t.equals(target)) {
				handlersList.add(entry.getValue());
			}
		}
		
		OrderComparator comparator = new OrderComparator();
		Collections.sort(handlersList, comparator);
		return handlersList;
	}	

}