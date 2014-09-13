package net.zomis.cardshifter.ecs.events;

import net.zomis.cardshifter.ecs.base.IEvent;
import net.zomis.cardshifter.ecs.base.Phase;
import net.zomis.cardshifter.ecs.components.PhaseController;

public abstract class PhaseChangeEvent implements IEvent {

	private final Phase oldPhase;
	private final Phase newPhase;
	private final PhaseController controller;

	public PhaseChangeEvent(PhaseController controller, Phase from, Phase to) {
		this.controller = controller;
		this.oldPhase = from;
		this.newPhase = to;
	}

	public Phase getNewPhase() {
		return newPhase;
	}
	
	public Phase getOldPhase() {
		return oldPhase;
	}
	
	public PhaseController getController() {
		return controller;
	}
	
}
