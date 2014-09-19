package com.cardshifter.server.model;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import net.zomis.cardshifter.ecs.actions.ActionComponent;
import net.zomis.cardshifter.ecs.actions.ECSAction;
import net.zomis.cardshifter.ecs.actions.TargetSet;
import net.zomis.cardshifter.ecs.base.ComponentRetriever;
import net.zomis.cardshifter.ecs.base.ECSGame;
import net.zomis.cardshifter.ecs.base.Entity;
import net.zomis.cardshifter.ecs.base.EntityRemoveEvent;
import net.zomis.cardshifter.ecs.cards.CardComponent;
import net.zomis.cardshifter.ecs.cards.ZoneChangeEvent;
import net.zomis.cardshifter.ecs.cards.ZoneComponent;
import net.zomis.cardshifter.ecs.components.PlayerComponent;
import net.zomis.cardshifter.ecs.phase.PhaseController;
import net.zomis.cardshifter.ecs.resources.ResourceValueChange;
import net.zomis.cardshifter.ecs.resources.Resources;
import net.zomis.cardshifter.ecs.usage.PhrancisGame;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.cardshifter.ai.CardshifterAI;
import com.cardshifter.ai.CompleteIdiot;
import com.cardshifter.server.clients.ClientIO;
import com.cardshifter.server.incoming.RequestTargetsMessage;
import com.cardshifter.server.incoming.UseAbilityMessage;
import com.cardshifter.server.main.FakeAIClientTCG;
import com.cardshifter.server.outgoing.CardInfoMessage;
import com.cardshifter.server.outgoing.EntityRemoveMessage;
import com.cardshifter.server.outgoing.PlayerMessage;
import com.cardshifter.server.outgoing.ResetAvailableActionsMessage;
import com.cardshifter.server.outgoing.UpdateMessage;
import com.cardshifter.server.outgoing.UseableActionMessage;
import com.cardshifter.server.outgoing.ZoneChangeMessage;
import com.cardshifter.server.outgoing.ZoneMessage;

public class TCGGame extends ServerGame {
	
	private static final Logger logger = LogManager.getLogger(TCGGame.class);
	private static final long AI_DELAY_SECONDS = 5;
	private final ECSGame game;
	private final ScheduledExecutorService aiPerform = Executors.newScheduledThreadPool(1);
	private final ComponentRetriever<CardComponent> card = ComponentRetriever.retreiverFor(CardComponent.class);
	private final PhaseController phases;

	private final CardshifterAI ai = new CompleteIdiot();
	private ComponentRetriever<PlayerComponent> playerData = ComponentRetriever.retreiverFor(PlayerComponent.class);
	
	public TCGGame(Server server, int id) {
		super(server, id);
		game = PhrancisGame.createGame();
		game.getEvents().registerHandlerAfter(ResourceValueChange.class, this::broadcast);
		game.getEvents().registerHandlerAfter(ZoneChangeEvent.class, this::zoneChange);
		game.getEvents().registerHandlerAfter(EntityRemoveEvent.class, this::remove);
		aiPerform.scheduleWithFixedDelay(this::aiPerform, 0, AI_DELAY_SECONDS, TimeUnit.SECONDS);
		phases = ComponentRetriever.singleton(game, PhaseController.class);
	}

	private void zoneChange(ZoneChangeEvent event) {
		Entity cardEntity = event.getCard();
		for (ClientIO io : this.getPlayers()) {
			Entity player = playerFor(io);
			io.sendToClient(new ZoneChangeMessage(event.getCard().getId(), event.getSource().getZoneId(), event.getDestination().getZoneId()));
			if (event.getDestination().isKnownTo(player) && !event.getSource().isKnownTo(player)) {
				io.sendToClient(new CardInfoMessage(event.getDestination().getZoneId(), cardEntity.getId(), Resources.map(cardEntity)));
			}
		}
	}
	
	private void remove(EntityRemoveEvent event) {
		this.send(new EntityRemoveMessage(event.getEntity().getId()));
	}
	
	private void broadcast(ResourceValueChange event) {
		if (getState() == GameState.NOT_STARTED) {
			// let the most information be sent when actually starting the game
			return;
		}
		
		Entity entity = event.getEntity();
		UpdateMessage updateEvent = new UpdateMessage(entity.getId(), event.getResource().toString(), event.getNewValue());
		
		if (card.has(entity)) {
			CardComponent cardData = card.get(entity);
			for (ClientIO io : this.getPlayers()) {
				Entity player = playerFor(io);
				if (cardData.getCurrentZone().isKnownTo(player)) {
					io.sendToClient(updateEvent);
				}
			}
		}
		else {
			// Player, Zone, or Game
			this.send(updateEvent);
		}
	}
	
	public void informAboutTargets(RequestTargetsMessage message, ClientIO client) {
		ECSAction action = findAction(message.getId(), message.getAction());
		TargetSet targetAction = action.getTargetSets().get(0);
		List<Entity> targets = targetAction.findPossibleTargets();
//		client.sendToClient(new ResetAvailableActionsMessage()); // not sure if this should be sent or not
		for (Entity target : targets) {
			client.sendToClient(new UseableActionMessage(message.getId(), message.getAction(), false, target.getId()));
		}
	}
	
	public Entity findTargetable(int entityId) {
		Optional<Entity> entity = game.findEntities(e -> e.getId() == entityId).stream().findFirst();
		return entity.orElse(null);
	}
	
	public ECSAction findAction(int entityId, String actionId) {
		Optional<Entity> entity = game.findEntities(e -> e.getId() == entityId).stream().findFirst();
		
		if (!entity.isPresent()) {
			throw new IllegalArgumentException("No such entity found");
		}
		Entity e = entity.get();
		if (e.hasComponent(ActionComponent.class)) {
			ActionComponent comp = e.getComponent(ActionComponent.class);
			if (comp.getActions().contains(actionId)) {
				return comp.getAction(actionId);
			}
			throw new IllegalArgumentException("No such action was found.");
		}
		throw new IllegalArgumentException(e + " does not have an action component");
	}
	
	public void handleMove(UseAbilityMessage message, ClientIO client) {
		if (!this.getPlayers().contains(client)) {
			throw new IllegalArgumentException("Client is not in this game: " + client);
		}
		if (phases.getCurrentEntity() != playerFor(client)) {
			throw new IllegalArgumentException("It's not that players turn: " + client);
		}
		
		ECSAction action = findAction(message.getId(), message.getAction());
		if (!action.getTargetSets().isEmpty()) {
			TargetSet targetAction = action.getTargetSets().get(0);
			targetAction.clearTargets();
			targetAction.addTarget(findTargetable(message.getTarget()));
		}
		action.perform(playerFor(client));
		
		// TODO: Add listener to game for ZoneMoves, inform players about card movements, and send CardInfoMessage when a card becomes known
		sendAvailableActions();
	}
	
	private void aiPerform() {
		if (this.getState() != GameState.RUNNING) {
			return;
		}
		
		for (ClientIO io : this.getPlayers()) {
			if (io instanceof FakeAIClientTCG) {
				Entity player = playerFor(io);
				if (phases.getCurrentEntity() != player) {
					continue;
				}
				ECSAction action = ai.getAction(player);
				if (action != null) {
					logger.info("AI Performs action: " + action);
					action.perform(player);
					sendAvailableActions();
					return;
				}
			}
		}
	}

	@Override
	protected boolean makeMove(Command command, int player) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected void updateStatus() {
		
	}

	public Entity playerFor(ClientIO io) {
		int index = this.getPlayers().indexOf(io);
		if (index < 0) {
			throw new IllegalArgumentException(io + " is not a valid player in this game");
		}
		return getPlayer(index);
	}
	
	private Entity getPlayer(int index) {
		return game.findEntities(entity -> entity.hasComponent(PlayerComponent.class) && entity.getComponent(PlayerComponent.class).getIndex() == index).get(0);
	}
	
	@Override
	protected void onStart() {
		game.startGame();
		this.getPlayers().stream().forEach(pl -> {
			Entity playerEntity = playerFor(pl);
			PlayerComponent plData = playerEntity.get(playerData);
			this.send(new PlayerMessage(playerEntity.getId(), plData.getIndex(), plData.getName(), Resources.map(playerEntity)));
		});
		this.game.findEntities(e -> true).stream().flatMap(e -> e.getSuperComponents(ZoneComponent.class).stream()).forEach(this::sendZone);
		this.sendAvailableActions();
	}
	
	private void sendAvailableActions() {
		for (ClientIO io : this.getPlayers()) {
			Entity player = playerFor(io);
			io.sendToClient(new ResetAvailableActionsMessage());
			if (phases.getCurrentEntity() == player) {
				getAllActions(game).filter(action -> action.isAllowed(player))
						.forEach(action -> io.sendToClient(new UseableActionMessage(action.getOwner().getId(), action.getName(), !action.getTargetSets().isEmpty())));
				
			}
		}
	}

	private static Stream<ECSAction> getAllActions(ECSGame game) {
		return game.getEntitiesWithComponent(ActionComponent.class)
			.stream()
			.flatMap(entity -> entity.getComponent(ActionComponent.class)
					.getECSActions().stream());
	}
	
	private void sendZone(ZoneComponent zone) {
		for (ClientIO io : this.getPlayers()) {
			Entity player = playerFor(io);
			io.sendToClient(constructZoneMessage(zone, player));
			if (zone.isKnownTo(player)) {
				zone.forEach(card -> this.sendCard(io, card));
			}
		}
	}
	
	private ZoneMessage constructZoneMessage(ZoneComponent zone, Entity player) {
		return new ZoneMessage(zone.getZoneId(), zone.getName(), zone.getOwner().getId(), zone.size(), zone.isKnownTo(player));
	}
	
	private void sendCard(ClientIO io, Entity card) {
		CardComponent cardData = card.getComponent(CardComponent.class);
		io.sendToClient(new CardInfoMessage(cardData.getCurrentZone().getZoneId(), card.getId(), Resources.map(card)));
	}

}
