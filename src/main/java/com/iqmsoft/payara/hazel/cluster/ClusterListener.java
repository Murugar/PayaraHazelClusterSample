package com.iqmsoft.payara.hazel.cluster;

import static com.iqmsoft.payara.hazel.cluster.AppConfig.COLORS;
import static com.iqmsoft.payara.hazel.cluster.AppConfig.HAZELCAST;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.InitialMembershipEvent;
import com.hazelcast.core.InitialMembershipListener;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.nurkiewicz.asyncretry.AsyncRetryExecutor;
import com.nurkiewicz.asyncretry.RetryExecutor;

@Startup
@Singleton
public class ClusterListener implements InitialMembershipListener {

    private final List<String> allColors;
    private final String defaultColor = "LightSalmon";
    private IMap<String, String> colorsInUse = null;

    public ClusterListener() {
        allColors = Arrays.asList(new String[] { "Crimson", "LightSeaGreen", "Gold", "RoyalBlue", "Black" });
    }

    @PostConstruct
    private void startup() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RetryExecutor executor = new AsyncRetryExecutor(scheduler).
                retryOn(NamingException.class).
                withExponentialBackoff(500, 2). // start with a delay of 500ms and double delay after each retry
                withMaxDelay(10000). // maximum delay should be 10 seconds
                withUniformJitter(); // add between +/- 100 ms randomly

        final CompletableFuture<HazelcastInstance> future = executor.getWithRetry(() -> {
            javax.naming.Context ctx = new InitialContext();
            return (HazelcastInstance) ctx.lookup(HAZELCAST);
        });

        future.thenAccept((HazelcastInstance hz) -> {
            colorsInUse = hz.getMap(COLORS);
            hz.getCluster().addMembershipListener(this);
        });
    }

    @Override
    public void init(InitialMembershipEvent initialMembershipEvent) {
        // we need to initialise ourself as a new member
        Logger.getAnonymousLogger().info("Initialising new Member...");
        initialiseNewMember(initialMembershipEvent.getCluster().getLocalMember().getUuid());
    }

    @Override
    public void memberAdded(MembershipEvent membershipEvent) {
        Logger.getAnonymousLogger().info("Adding new Member...");
        initialiseNewMember(membershipEvent.getMember().getUuid());
    }

    public void initialiseNewMember(String uuid) {
        String colorToAdd = getUnusedColor();
        String assignedColor = colorsInUse.putIfAbsent(uuid, colorToAdd);
        Logger.getAnonymousLogger().info("Member " + uuid + " added. Colors in use: " + colorsInUse.size());
        if (assignedColor == null) {
            Logger.getAnonymousLogger().info("Color " + colorToAdd + " assigned to new Member " + uuid);
        }
        else {
            Logger.getAnonymousLogger().info("Color " + colorToAdd + " not assigned, Member " + uuid + " already had Color " + assignedColor);
        }
    }

    @Override
    public void memberRemoved(MembershipEvent membershipEvent) {
        String uuidToRemove = membershipEvent.getMember().getUuid();
        colorsInUse.remove(uuidToRemove);
        Logger.getAnonymousLogger().info("Member " + uuidToRemove + " removed. Colors in use: " + colorsInUse.size());
    }

    @Override
    public void memberAttributeChanged(MemberAttributeEvent memberAttributeEvent) {
        // System.err.println("Member attribute changed: " + memberAttributeEvent);
    }

    private String getUnusedColor() {
        Logger.getAnonymousLogger().info("Colors in use: " + colorsInUse.size() + " of " + allColors.size());
        if (colorsInUse.size() >= allColors.size()) {
            // all predefined colors are taken, so for any overflow nodes let's use the default color
            return defaultColor;
        }
        else {
            // use a color that is not already taken
            List<String> colorsAvailable = new ArrayList<>(allColors);
            for (String color : colorsInUse.values()) {
                colorsAvailable.remove(color);
            }
            return colorsAvailable.get(ThreadLocalRandom.current().nextInt(0, colorsAvailable.size()));
        }
    }

}
