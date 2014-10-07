package com.cardshifter.modapi.ai;

import java.util.Objects;

import com.cardshifter.modapi.base.Component;

public class AIComponent extends Component {
	
	private CardshifterAI ai;
	private long delay = 4000;

	public AIComponent(CardshifterAI ai) {
		setAI(ai);
	}
	
	public void setAI(CardshifterAI ai) {
		this.ai = Objects.requireNonNull(ai);
	}
	
	public CardshifterAI getAI() {
		return ai;
	}

	public long getDelay() {
		return delay;
	}
	
	public void setDelay(long delay) {
		this.delay = delay;
	}
	
}
