package org.spout.engine.player;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.spout.api.Spout;
import org.spout.api.data.ValueHolder;
import org.spout.api.entity.Entity;
import org.spout.api.entity.component.Controller;
import org.spout.api.event.Result;
import org.spout.api.event.player.PlayerChatEvent;
import org.spout.api.event.server.data.RetrieveDataEvent;
import org.spout.api.event.server.permissions.PermissionGetGroupsEvent;
import org.spout.api.event.server.permissions.PermissionGroupEvent;
import org.spout.api.event.server.permissions.PermissionNodeEvent;
import org.spout.api.geo.World;
import org.spout.api.geo.cuboid.Chunk;
import org.spout.api.geo.discrete.Transform;
import org.spout.api.player.Player;
import org.spout.api.player.PlayerController;
import org.spout.api.player.PlayerInputState;
import org.spout.api.protocol.Message;
import org.spout.api.protocol.NetworkSynchronizer;
import org.spout.api.protocol.Session;
import org.spout.api.util.thread.DelayedWrite;
import org.spout.api.util.thread.SnapshotRead;
import org.spout.api.util.thread.Threadsafe;
import org.spout.engine.SpoutConfiguration;
import org.spout.engine.SpoutEngine;
import org.spout.engine.entity.SpoutEntity;
import org.spout.engine.protocol.SpoutSession;
import org.spout.engine.util.TextWrapper;

public class SpoutPlayer extends SpoutEntity implements Player {
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

	public SpoutPlayer(String name, SpoutSession session, SpoutEngine engine, Transform transform, Controller controller) {
		super(engine, transform, controller, SpoutConfiguration.VIEW_DISTANCE.getInt() * Chunk.BLOCKS.SIZE);
		sessionLive.set(session);
		this.session = session;
		online = true;
		onlineLive.set(true);
		this.name = name;
		displayName.set(name);
		hashcode = name.hashCode();
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

	@DelayedWrite
	public boolean disconnect() {
		if (!onlineLive.compareAndSet(true, false)) {
			// player was already offline
			return false;
		}

		sessionLive.set(null);
		synchronizerLive.set(null);
		return true;
	}

	@DelayedWrite
	public boolean connect(SpoutSession session) {
		if (!onlineLive.compareAndSet(false, true)) {
			// player was already online
			return false;
		}

		sessionLive.set(session);

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
	}
}
