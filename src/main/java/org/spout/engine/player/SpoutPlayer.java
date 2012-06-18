package org.spout.engine.player;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.spout.api.Spout;
import org.spout.api.data.ValueHolder;
import org.spout.api.entity.component.Controller;
import org.spout.api.event.Result;
import org.spout.api.event.player.PlayerChatEvent;
import org.spout.api.event.server.data.RetrieveDataEvent;
import org.spout.api.event.server.permissions.PermissionGetGroupsEvent;
import org.spout.api.event.server.permissions.PermissionGroupEvent;
import org.spout.api.event.server.permissions.PermissionNodeEvent;
import org.spout.api.geo.World;
import org.spout.api.geo.cuboid.Chunk;
import org.spout.api.geo.discrete.Point;
import org.spout.api.geo.discrete.Transform;
import org.spout.api.math.Quaternion;
import org.spout.api.math.Vector3;
import org.spout.api.player.Player;
import org.spout.api.player.PlayerController;
import org.spout.api.player.PlayerInputState;
import org.spout.api.protocol.Message;
import org.spout.api.protocol.NetworkSynchronizer;
import org.spout.api.util.thread.DelayedWrite;
import org.spout.api.util.thread.SnapshotRead;
import org.spout.api.util.thread.Threadsafe;
import org.spout.engine.SpoutConfiguration;
import org.spout.engine.SpoutEngine;
import org.spout.engine.entity.SpoutEntity;
import org.spout.engine.protocol.SpoutSession;
import org.spout.engine.util.TextWrapper;
import org.spout.engine.world.SpoutWorld;

/**
 * Implementation of {@link Player} as a subclass of SpoutEntity
 *
 * <strong>How offline players work:</strong>
 * <p>
 *     A SpoutEntity that is constructed as offline will have its chunkLive as null,
 *     which will prevent it from being spawned as an entity. Its session and network
 *     synchronizer will also be null.
 * </p>
 * <strong>Player Data:</strong>
 * <p>
 *     Methods in {@link SpoutEntity} and {@link Player} that modify player data should set shouldSave to
 *     true, which will make sure that data is saved in the player's onTick. Data should
 *     also be saved when the player is disconnected. Player data should be loaded
 *     when a player is constructed or a player comes online.
 * </p>
 */
public class SpoutPlayer extends SpoutEntity implements Player {
	private static final int SAVE_DELAY = 20;
	private final AtomicReference<SpoutSession> sessionLive = new AtomicReference<SpoutSession>();
	private SpoutSession session;
	private final String name;
	private final AtomicReference<String> displayName = new AtomicReference<String>();
	private final AtomicReference<NetworkSynchronizer> synchronizerLive = new AtomicReference<NetworkSynchronizer>();
	private NetworkSynchronizer synchronizer;
	private final AtomicBoolean onlineLive = new AtomicBoolean(false);
	private boolean online;
	private final int hashcode;
	private final PlayerInputState inputState = new PlayerInputState();
	private final AtomicBoolean shouldSave = new AtomicBoolean();
	private int saveTicks;

	public SpoutPlayer(String name, SpoutEngine engine) {
		super(engine, (Transform) null, null);
		this.name = name;
		displayName.set(name);
		hashcode = name.hashCode();
		load();
	}

	public SpoutPlayer(String name, SpoutSession session, SpoutEngine engine, Transform transform, Controller controller) {
		super(engine, transform, controller, SpoutConfiguration.VIEW_DISTANCE.getInt() * Chunk.BLOCKS.SIZE);
		this.name = name;
		displayName.set(name);
		hashcode = name.hashCode();
		connect(session);
	}

	@Override
	public PlayerController getController() {
		return (PlayerController) super.getController();
	}

	@Override
	@Threadsafe
	public String getName() {
		return name;
	}

	@Override
	@Threadsafe
	public String getDisplayName() {
		return displayName.get();
	}

	@Override
	@Threadsafe
	public void setDisplayName(String name) {
		displayName.set(name);
	}

	@Override
	@SnapshotRead
	public SpoutSession getSession() {
		return session;
	}

	@Override
	@SnapshotRead
	public boolean isOnline() {
		return online;
	}

	public boolean isOnlineLive() {
		return onlineLive.get();
	}

	@Override
	@SnapshotRead
	public InetAddress getAddress() {
		if (session != null && session.getAddress() != null) {
			return session.getAddress().getAddress();
		}
		return null;
	}

	/**
	 * Called when the player disconnects. Handles all the removing the entity from the
	 * world and marking it as dead.
	 *
	 * @return Whether disconnecting the player was successful (whether the player was connected when the method was called)
	 */
	@DelayedWrite
	public boolean disconnect() {
		if (!onlineLive.compareAndSet(true, false)) {
			// player was already offline
			return false;
		}
		this.save();
		this.shouldSave.set(false);
		((SpoutWorld) getWorld()).removePlayer(this);
		this.kill();

		sessionLive.set(null);
		synchronizerLive.set(null);
		return true;
	}

	/**
	 * Called when a player reconnects. Handles bringing the entity back to life and loading player data.
	 * @param session The session to connect the player to
	 * @return Whether connecting the player was successful (whether the player was disconnected when the method was called)
	 */
	@DelayedWrite
	public boolean connect(SpoutSession session) {
		if (!onlineLive.compareAndSet(false, true)) {
			// player was already online
			return false;
		}

		this.load();
		setupInitialChunk(transform);
		sessionLive.set(session);
		session.setPlayer(this);
		getWorld().spawnEntity(this);

		copyToSnapshot();
		return true;
	}

	@Override
	public void chat(final String message) {
		if (message.startsWith("/")) {
			Spout.getEngine().processCommand(this, message.substring(1));
		} else {
			PlayerChatEvent event = Spout.getEngine().getEventManager().callEvent(new PlayerChatEvent(this, message));
			if (event.isCancelled()) {
				return;
			}
			String formattedMessage;
			try {
				formattedMessage = String.format(event.getFormat(), getDisplayName(), event.getMessage());
			} catch (Throwable t) {
				return;
			}
			Spout.getEngine().broadcastMessage(formattedMessage);
		}
	}

	public boolean sendMessage(String message) {
		boolean success = false;
		for (String line : TextWrapper.wrapText(message)) {
			success |= sendRawMessage(line);
		}
		return success;
	}

	public boolean sendRawMessage(String message) {
		Message chatMessage = getSession().getPlayerProtocol().getChatMessage(message);
		if (message == null) {
			return false;
		}

		session.send(chatMessage);
		return true;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof SpoutPlayer) {
			SpoutPlayer p = (SpoutPlayer) obj;
			if (p.hashCode() != hashCode()) {
				return false;
			} else if (p == this) {
				return true;
			} else {
				return name.equals(p.name);
			}
		}
		return false;
	}

	@Override
	public int hashCode() {
		return hashcode;
	}

	public void copyToSnapshot() {
		session = sessionLive.get();
		online = onlineLive.get();
		synchronizer = synchronizerLive.get();
	}

	@Override
	public boolean hasPermission(String node) {
		return hasPermission(getWorld(), node);
	}

	@Override
	public boolean hasPermission(World world, String node) {
		PermissionNodeEvent event = Spout.getEngine().getEventManager().callEvent(new PermissionNodeEvent(world, this, node));
		if (event.getResult() == Result.DEFAULT) {
			return false;
		}

		return event.getResult().getResult();
	}

	@Override
	public boolean isInGroup(String group) {
		PermissionGroupEvent event = Spout.getEngine().getEventManager().callEvent(new PermissionGroupEvent(getWorld(), this, group));
		return event.getResult();
	}

	@Override
	public String[] getGroups() {
		PermissionGetGroupsEvent event = Spout.getEngine().getEventManager().callEvent(new PermissionGetGroupsEvent(getWorld(), this));
		return event.getGroups();
	}

	@Override
	public boolean isGroup() {
		return false;
	}

	@Override
	public ValueHolder getData(String node) {
		RetrieveDataEvent event = Spout.getEngine().getEventManager().callEvent(new RetrieveDataEvent(this, node));
		return event.getResult();
	}

	@Override
	public void kick() {
		kick("Kicked");
	}

	@Override
	public void kick(String reason) {
		if (reason == null) {
			throw new IllegalArgumentException("reason cannot be null");
		}
		session.disconnect(reason);
	}

	@Override
	public void setNetworkSynchronizer(NetworkSynchronizer synchronizer) {
		if (synchronizer == null && !onlineLive.get()) {
			synchronizerLive.set(null);
		} else if (!synchronizerLive.compareAndSet(null, synchronizer)) {
			throw new IllegalArgumentException("Network synchronizer may only be set once for a given player login");
		}
	}

	@Override
	public NetworkSynchronizer getNetworkSynchronizer() {
		NetworkSynchronizer s = synchronizer;
		if (s != null) {
			return s;
		}

		return synchronizerLive.get();
	}

	@Override
	public PlayerInputState input() {
		return inputState;
	}

	@Override
	public void onTick(float dt) {
		if (isOnline()) {
			super.onTick(dt);
		}

		if (shouldSave.get() && ++saveTicks >= SAVE_DELAY) {
			if (shouldSave.compareAndSet(true, false)) {
				save();
				saveTicks = 0;
			}
		}
	}

	@Override
	public void setTransform(Transform transform) {
		super.setTransform(transform);
		shouldSave.set(true);
	}

	@Override
	public void setPosition(Point position) {
		super.setPosition(position);
		shouldSave.set(true);
	}

	@Override
	public void setRotation(Quaternion rotation) {
		super.setRotation(rotation);
		shouldSave.set(true);
	}

	@Override
	public void setScale(Vector3 scale) {
		super.setScale(scale);
		shouldSave.set(true);
	}

	public void load() {
		setTransform(engine.getDefaultWorld().getSpawnPoint());
		// TODO: Player data loading
	}

	public void save() {
		// TODO: Player data saving
	}
}
