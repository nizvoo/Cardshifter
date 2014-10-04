package com.cardshifter.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import com.cardshifter.api.CardshifterConstants;
import com.cardshifter.api.incoming.LoginMessage;
import com.cardshifter.api.incoming.RequestTargetsMessage;
import com.cardshifter.api.incoming.StartGameRequest;
import com.cardshifter.api.incoming.UseAbilityMessage;
import com.cardshifter.api.messages.Message;
import com.cardshifter.api.outgoing.AvailableTargetsMessage;
import com.cardshifter.api.outgoing.CardInfoMessage;
import com.cardshifter.api.outgoing.ClientDisconnectedMessage;
import com.cardshifter.api.outgoing.EntityRemoveMessage;
import com.cardshifter.api.outgoing.GameOverMessage;
import com.cardshifter.api.outgoing.NewGameMessage;
import com.cardshifter.api.outgoing.PlayerMessage;
import com.cardshifter.api.outgoing.ResetAvailableActionsMessage;
import com.cardshifter.api.outgoing.UpdateMessage;
import com.cardshifter.api.outgoing.UseableActionMessage;
import com.cardshifter.api.outgoing.WaitMessage;
import com.cardshifter.api.outgoing.WelcomeMessage;
import com.cardshifter.api.outgoing.ZoneChangeMessage;
import com.cardshifter.api.outgoing.ZoneMessage;
import com.cardshifter.client.views.CardView;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;

public class GameClientController {
	
	@FXML private AnchorPane rootPane;
	@FXML private Label loginMessage;
	@FXML private ListView<String> serverMessages;
	@FXML private VBox opponentStatBox;
	@FXML private VBox playerStatBox;
	@FXML private HBox actionBox;
	
	@FXML private HBox opponentHandPane;
	@FXML private HBox opponentBattlefieldPane;
	@FXML private Pane opponentDeckPane;
	@FXML private Label opponentDeckLabel;
	@FXML private HBox playerHandPane;
	@FXML private HBox playerBattlefieldPane;
	@FXML private Pane playerDeckPane;
	@FXML private Label playerDeckLabel;
	
	private final ObjectMapper mapper = new ObjectMapper();
	private final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
	private Socket socket;
	private InputStream in;
	private OutputStream out;
	private String ipAddress;
	private int port;
	
	private int gameId;
	private int playerIndex;
	private Thread listenThread;
	private Thread playThread;
	
	private int opponentId;
	private int opponentHandId;
	private int opponentBattlefieldId;
	private int opponentDeckId;
	private Map<Integer, Integer> opponentDeckEntityIds = new HashMap<>();
	private int playerId;
	private int playerHandId;
	private int playerBattlefieldId;
	private int playerDeckId;
	private Map<Integer, Integer> playerDeckEntityIds = new HashMap<>();
	
	private final Map<String, Integer> playerStatBoxMap = new HashMap<>();
	private final Map<String, Integer> opponentStatBoxMap = new HashMap<>();
	private final Map<Integer, ZoneView<?>> zoneViewMap = new HashMap<>();
	private List<UseableActionMessage> savedMessages = new ArrayList<>();
	
	public void acceptIPAndPort(String ipAddress, int port) {
		// this is passed into this object after it is automatically created by the FXML document
		this.ipAddress = ipAddress;
		this.port = port;
	}
	public boolean connectToGame() {
		// this is called on the object from the Game launcher before the scene is displayed
		try {
			this.socket = new Socket(this.ipAddress, this.port);
			this.out = socket.getOutputStream();
			this.in = socket.getInputStream();
			mapper.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
			mapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
			this.listenThread = new Thread(this::listen);
			this.listenThread.start();
		} catch (IOException ex) {
			System.out.println("Connection Failed");
			return false;
		}
		this.playThread = new Thread(this::play);
		this.playThread.start();	
		return true;
	}
	private void play() {
		// this method only runs once at the start
		String name = "Player" + new Random().nextInt(100);
		this.send(new LoginMessage(name));
		
		try {
			WelcomeMessage response = (WelcomeMessage) messages.take();
			if (!response.isOK()) {
				return;
			}
			//display the welcome message on the screen
			Platform.runLater(() -> loginMessage.setText(response.getMessage()));
		} catch (Exception e) {
			System.out.println("Server message not OK");
			e.printStackTrace();
		}
		
		this.send(new StartGameRequest(-1, CardshifterConstants.VANILLA));
		
		try {
			Message message = messages.take();
			if (message instanceof WaitMessage) {	
				//display the wait message on the screen
				String displayMessage = ((WaitMessage)message).getMessage();
				Platform.runLater(() -> loginMessage.setText(displayMessage));
				message = messages.take();
			}
			this.gameId = ((NewGameMessage) message).getGameId();
		} catch (Exception e) {
			System.out.println("Invalid response from opponent");
			e.printStackTrace();
		}
		
		Platform.runLater(() -> this.repaintDeckLabels());
	}

	private void listen() {
		while (true) {
			try {
				MappingIterator<Message> values = mapper.readValues(new JsonFactory().createParser(this.in), Message.class);
				while (values.hasNextValue()) {
					Message message = values.next();
					try {
						messages.put(message);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					//This is where all the magic happens for message handling
					Platform.runLater(() -> this.processMessageFromServer(message));
				}
			} catch (SocketException e) {
				Platform.runLater(() -> loginMessage.setText(e.getMessage()));
				return;
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public void createAndSendMessage(UseableActionMessage action) {
		try {
			if (action.isTargetRequired()) {
				this.send(new RequestTargetsMessage(gameId, action.getId(), action.getAction()));
			} else {
				this.send(new UseAbilityMessage(gameId, action.getId(), action.getAction(), action.getTargetId()));
				
				this.clearTargetableFromAllCards();
			}
		} catch (NumberFormatException | IndexOutOfBoundsException ex) {
			System.out.println("Not a valid action");
		}
		
		//A new list of actions will be sent back from the server, so it is okay to clear them
		this.actionBox.getChildren().clear();
		this.clearActiveFromAllCards();
	}
	
	private void send(Message message) {
		try {
			System.out.println("Sending: " + this.mapper.writeValueAsString(message));
			this.mapper.writeValue(out, message);
		} catch (IOException e) {
			System.out.println("Error sending message: " + message);
			throw new RuntimeException(e);
		}
	}
	
	private void processMessageFromServer(Message message) {
		
		serverMessages.getItems().add(message.toString());
		
		//this is for diagnostics so I can copy paste the messages to know their format
		System.out.println(message.toString());
		
		if (message instanceof NewGameMessage) {
			this.processNewGameMessage((NewGameMessage) message);
		} else if (message instanceof PlayerMessage) {
			this.processPlayerMessage((PlayerMessage)message);
		} else if (message instanceof ZoneMessage) {
			this.assignZoneIdForZoneMessage((ZoneMessage)message);
		} else if (message instanceof CardInfoMessage) {
			this.processCardInfoMessage((CardInfoMessage)message);
		} else if (message instanceof UseableActionMessage) {
			this.savedMessages.add((UseableActionMessage)message);
			this.processUseableActionMessage((UseableActionMessage)message);
		} else if (message instanceof UpdateMessage) {
			this.processUpdateMessage((UpdateMessage)message);
		} else if (message instanceof ZoneChangeMessage) {
			this.processZoneChangeMessage((ZoneChangeMessage)message);
		} else if (message instanceof EntityRemoveMessage) {
			this.processEntityRemoveMessage((EntityRemoveMessage)message);
		} else if (message instanceof AvailableTargetsMessage) {
			this.processAvailableTargetsMessage((AvailableTargetsMessage)message);
		} else if (message instanceof ResetAvailableActionsMessage) {
			//this.processResetAvailableActionsMessage((ResetAvailableActionsMessage)message);
			this.clearSavedActions();
		} else if (message instanceof ClientDisconnectedMessage) {
			this.processClientDisconnectedMessage((ClientDisconnectedMessage)message);
		} else if (message instanceof GameOverMessage) {
			this.processGameOverMessage((GameOverMessage)message);
		}
	}
	
	private void processNewGameMessage(NewGameMessage message) {
		this.playerIndex = message.getPlayerIndex();
		System.out.println(String.format("You are player: %d", this.playerIndex));
	}
	
	private void processPlayerMessage(PlayerMessage message) {
		if (message.getIndex() == this.playerIndex) {
			this.playerId = message.getId();
			this.processPlayerMessageForPlayer(message, playerStatBox, playerStatBoxMap);
		} else {
			this.opponentId = message.getId();
			this.processPlayerMessageForPlayer(message, opponentStatBox, opponentStatBoxMap);
			Platform.runLater(() -> this.loginMessage.setText("Opponent Connected"));
		}
	}
	private void processPlayerMessageForPlayer(PlayerMessage message, Pane statBox, Map<String, Integer> playerMap) {
		statBox.getChildren().clear();
		Map<String, Integer> sortedMap = new TreeMap<>(message.getProperties());
		playerMap.putAll(sortedMap);
		for (Map.Entry<String, Integer> entry : sortedMap.entrySet()) {
			String key = entry.getKey();
			statBox.getChildren().add(new Label(key));
			int value = entry.getValue();
			statBox.getChildren().add(new Label(String.format("%d",value)));
		}
	}
	
	private void assignZoneIdForZoneMessage(ZoneMessage message) {
		if (!this.zoneViewMap.containsKey(message.getId())) {
			if (message.getName().equals("Battlefield")) {
				if(message.getOwner() == this.playerId) {
					this.playerBattlefieldId = message.getId();
					this.zoneViewMap.put(message.getId(), new BattlefieldZoneView(message.getId(), playerBattlefieldPane));
					
				} else {
					this.opponentBattlefieldId = message.getId();
					this.zoneViewMap.put(message.getId(), new BattlefieldZoneView(message.getId(), opponentBattlefieldPane));
				}
			} else if (message.getName().equals("Hand")) {
				if (message.getOwner() == this.playerId) {
					this.playerHandId = message.getId();
					this.zoneViewMap.put(message.getId(), new PlayerHandZoneView(message.getId(), playerHandPane));
				} else {
					this.opponentHandId = message.getId();
					this.zoneViewMap.put(this.opponentHandId, new ZoneView<CardView>(message.getId(), opponentHandPane));
					
					this.createOpponentHand(message.getSize());
				}
			} else if (message.getName().equals("Deck")) {
				if (message.getOwner() == this.playerId) {
					this.playerDeckId = message.getId();
					for (int i = 0; i < message.getEntities().length; i++) {
						this.playerDeckEntityIds.put(i, message.getEntities()[i]);
					}
					this.repaintDeckLabels();
					this.zoneViewMap.put(message.getId(), new ZoneView<CardView>(message.getId(), playerDeckPane));
				} else {
					this.opponentDeckId = message.getId();
					for (int i = 0; i < message.getEntities().length; i++) {
						this.opponentDeckEntityIds.put(i, message.getEntities()[i]);
					}
					this.repaintDeckLabels();
					this.zoneViewMap.put(message.getId(), new ZoneView<CardView>(message.getId(), opponentDeckPane));
				}
			}
		}
	}

	private void processCardInfoMessage(CardInfoMessage message) {
		int targetZone = message.getZone();
		
		if (targetZone == opponentBattlefieldId) {
			this.addCardToOpponentBattlefieldPane(message);
		} else if (targetZone == opponentHandId) {
			this.addCardToOpponentHandPane(message);
		} else if (targetZone == playerBattlefieldId) {
			this.addCardToPlayerBattlefieldPane(message);
		} else if (targetZone == playerHandId) {
			this.addCardToPlayerHandPane(message);
		}
	}	
	private void addCardToOpponentBattlefieldPane(CardInfoMessage message) {
		BattlefieldZoneView opponentBattlefield = getZoneView(opponentBattlefieldId);
		CardBattlefieldDocumentController card = new CardBattlefieldDocumentController(message, this);
		opponentBattlefield.addPane(message.getId(), card);
	}
	private void addCardToOpponentHandPane(CardInfoMessage message) {
		// this is unused because *KNOWN* cards don't pop up in opponent hand without reason (at least not now)
	}
	private void addCardToPlayerBattlefieldPane(CardInfoMessage message) {
		// this is unused because cards don't pop up in the battlefield magically, they are *moved* there (at least for now)
	}
	private void addCardToPlayerHandPane(CardInfoMessage message) {
		PlayerHandZoneView playerHand = getZoneView(playerHandId);
		CardHandDocumentController card = new CardHandDocumentController(message, this);
		playerHand.addPane(message.getId(), card);
	}
	
	private void processUseableActionMessage(UseableActionMessage message) {
		if (message.getAction().equals("End Turn")) {
			this.createEndTurnButton(message);
		}
		
		ZoneView<?> zoneView = getZoneViewForCard(message.getId());
		System.out.println("Usable message: " + message + " inform zone " + zoneView);
		if (zoneView == null) {
			return;
		}
		if (message.getAction().equals("Attack")) {
			((BattlefieldZoneView)zoneView).setCardCanAttack(message.getId(),message);
		} else {
			zoneView.setCardActive(message.getId(), message);
		}
		
	}
	
	private void processUpdateMessage(UpdateMessage message) {
		if (message.getId() == this.playerId) {
			this.processUpdateMessageForPlayer(playerStatBox, message, playerStatBoxMap);
		} else if (message.getId() == this.opponentId) {
			this.processUpdateMessageForPlayer(opponentStatBox, message, opponentStatBoxMap);
		} else {
			this.processUpdateMessageForCard(message);
		}
	}
	
	private void processUpdateMessageForPlayer(Pane statBox, UpdateMessage message, Map<String, Integer> playerMap) {
		String key = (String)message.getKey();
		Integer value = (Integer)message.getValue();
		playerMap.put(key, value);
	
		this.repaintStatBox(statBox, playerMap);
	}
	
	private void processUpdateMessageForCard(UpdateMessage message) {
		ZoneView<?> zoneView = getZoneViewForCard(message.getId());
		if (zoneView != null) {
			zoneView.updateCard(message.getId(), message);
		}
	}
	
	private void processZoneChangeMessage(ZoneChangeMessage message) {
		int sourceZoneId = message.getSourceZone();
		int destinationZoneId = message.getDestinationZone();
		int cardId = message.getEntity();
		
		if (sourceZoneId == opponentDeckId) {
			this.removeCardFromDeck(sourceZoneId, cardId);
			if (destinationZoneId == opponentHandId) {
				this.addCardToOpponentHand();
			}
		} else if (sourceZoneId == playerDeckId) {
			this.removeCardFromDeck(sourceZoneId, cardId);
		} else if (sourceZoneId == opponentHandId) {
			this.removeCardFromOpponentHand();
		}
		
		if (this.zoneViewMap.containsKey(sourceZoneId) && this.zoneViewMap.containsKey(destinationZoneId)) {
			if (sourceZoneId == playerHandId) {
				PlayerHandZoneView sourceZone = getZoneView(sourceZoneId);
				CardHandDocumentController card = sourceZone.getCard(cardId);
				
				CardBattlefieldDocumentController newCard = new CardBattlefieldDocumentController(card.getCard(), this);
				BattlefieldZoneView destinationZone = getZoneView(destinationZoneId);
				destinationZone.addPane(cardId, newCard);
			
				sourceZone.removePane(cardId);
			}
		}
	}
	
	private void processEntityRemoveMessage(EntityRemoveMessage message) {
	
		if (this.opponentDeckEntityIds.values().contains(message.getEntity())) {
			this.removeCardFromDeck(this.opponentDeckId, message.getEntity());
		} else if (this.playerDeckEntityIds.values().contains(message.getEntity())) {
			this.removeCardFromDeck(this.playerDeckId, message.getEntity());
		}
		
		ZoneView<?> zoneView = getZoneViewForCard(message.getEntity());
		if (zoneView != null) {
			zoneView.removePane(message.getEntity());
		}
	}
	
	private void processAvailableTargetsMessage(AvailableTargetsMessage message) {
		if (message.getTargets().length == 0) {
			// there are no targets to choose from, so cancel the action
			UseableActionMessage newMessage = new UseableActionMessage(this.playerId, "End Turn", false, 0);
			this.createEndTurnButton(newMessage);
			this.createCancelActionsButton();
		}
		for (int i = 0; i < message.getTargets().length; i++) {
			int target = message.getTargets()[i];
			if (target != this.opponentId) {
				for (ZoneView<?> zoneView : this.zoneViewMap.values()) {
					if (zoneView instanceof BattlefieldZoneView) {
						if (zoneView.getAllIds().contains(target)) {
							UseableActionMessage newMessage = new UseableActionMessage(message.getEntity(), message.getAction(), false, target);
							((BattlefieldZoneView)zoneView).setCardTargetable(target, newMessage);
						}
					}
				}
				this.createCancelActionsButton();
			} else {
				// automatically target opponent
				UseableActionMessage newMessage = new UseableActionMessage(message.getEntity(), message.getAction(), false, target);
				this.createAndSendMessage(newMessage);
			}
		}
	}
	
	private void processClientDisconnectedMessage(ClientDisconnectedMessage message) {
		Platform.runLater(() -> this.loginMessage.setText("Opponent Left"));
	}
	
	private void processGameOverMessage(GameOverMessage message) {
		Platform.runLater(() -> this.loginMessage.setText("Game Over!"));
		this.stopThreads();
	}
	
	private void removeCardFromDeck(int zoneId, int cardId) {
		if (this.opponentDeckId == zoneId) {
			if (this.opponentDeckEntityIds.values().contains(cardId)) {
				this.opponentDeckEntityIds.values().remove(cardId);
			}
 		} else if (this.playerDeckId == zoneId) {
			if (this.playerDeckEntityIds.values().contains(cardId)) {
				this.playerDeckEntityIds.values().remove(cardId);
			}
		}
		this.repaintDeckLabels();
	}
	
	private void createEndTurnButton(UseableActionMessage message) {
		createActionButton(message.getAction(), () -> createAndSendMessage(message));
	}
	
	private void createCancelActionsButton() {
		createActionButton("Cancel", () -> cancelAction());
	}
	
	private void createActionButton(String label, Runnable action) {
		double paneHeight = actionBox.getHeight();
		double paneWidth = actionBox.getWidth();
		
		int maxActions = 8;
		double actionWidth = paneWidth / maxActions;
		
		ActionButton actionButton = new ActionButton(label, actionWidth, paneHeight, action);
		actionBox.getChildren().add(actionButton);
	}
	
	private void clearSavedActions() {
		this.savedMessages.clear();
		this.actionBox.getChildren().clear();
	}
	
	public void cancelAction() {
		this.clearActiveFromAllCards();
		this.clearTargetableFromAllCards();
		this.actionBox.getChildren().clear();
		
		for (UseableActionMessage message : this.savedMessages) {
			Platform.runLater(() -> this.processUseableActionMessage(message));
		}
	}
	
	private void clearActiveFromAllCards() {
		for (ZoneView<?> zoneView : this.zoneViewMap.values()) {
			if (zoneView instanceof BattlefieldZoneView) {
				((BattlefieldZoneView)zoneView).removeActiveAllCards();
			} else if (zoneView instanceof PlayerHandZoneView) {
				((PlayerHandZoneView)zoneView).removeActiveAllCards();
			}
		}
	}
	
	private void clearTargetableFromAllCards() {
		for (ZoneView<?> zoneView : this.zoneViewMap.values()) {
			if (zoneView instanceof BattlefieldZoneView) {
				((BattlefieldZoneView)zoneView).removeTargetableAllCards();
			}
		}
	}
	
	private void repaintStatBox(Pane statBox, Map<String, Integer> playerMap) {
		statBox.getChildren().clear();
		for (Map.Entry<String, Integer> entry : playerMap.entrySet()) {
			String key = entry.getKey();
			statBox.getChildren().add(new Label(key));
			int value = entry.getValue();
			statBox.getChildren().add(new Label(String.format("%d",value)));
		}
	}
	
	private void repaintDeckLabels() {
		this.opponentDeckLabel.setText(String.format("%d", this.opponentDeckEntityIds.values().size()));
		this.playerDeckLabel.setText(String.format("%d", this.playerDeckEntityIds.values().size()));
	}
	
	private void createOpponentHand(int size) {
		for(int i = 0; i < size; i++) {
			this.addCardToOpponentHand();
		}
	}
	
	private void addCardToOpponentHand() {
		ZoneView<?> opponentHand = this.zoneViewMap.get(this.opponentHandId);
		int handSize = opponentHand.getSize();
		opponentHand.addSimplePane(handSize, this.cardForOpponentHand());
	}
	
	private void removeCardFromOpponentHand() {
		ZoneView<?> opponentHand = this.zoneViewMap.get(this.opponentHandId);
		int handSize = opponentHand.getSize();
		opponentHand.removeRawPane(handSize - 1);
	}
	
	private Pane cardForOpponentHand() {
		double paneHeight = opponentHandPane.getHeight();
		double paneWidth = opponentHandPane.getWidth();
		
		int maxCards = 10;
		double cardWidth = paneWidth / maxCards;
		
		Pane card = new Pane();
		Rectangle cardBack = new Rectangle(0,0,cardWidth,paneHeight);
		cardBack.setFill(Color.AQUAMARINE);
		card.getChildren().add(cardBack);
		
		return card;
	}
	
	private void stopThreads() {
		this.listenThread.interrupt();
		this.playThread.interrupt();
		
		//Uncomment these lines to cure the exception
		//this.listenThread.stop();
		//this.playThread.stop();
	}
	
	private void breakConnection() {
		try {
			this.in.close();
			this.out.close();
		} catch (Exception e) {
			System.out.println("Failed to break connection");
		}
	}
	
	public void closeGame() {
		this.stopThreads();
		this.breakConnection();
	}
	
	@SuppressWarnings("unchecked")
	private <T extends ZoneView<?>> T getZoneView(int id) {
		return (T) this.zoneViewMap.get(id);
	}
	
	private ZoneView<?> getZoneViewForCard(int id) {
		for (ZoneView<?> zoneView : this.zoneViewMap.values()) {
			if (zoneView.getAllIds().contains(id)) {
				return zoneView;
			}
		}
		return null;
	}
}

