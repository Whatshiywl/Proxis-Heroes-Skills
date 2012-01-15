package com.herocraftonline.dev.heroes.skill.skills;


import com.herocraftonline.dev.heroes.Heroes;
import com.herocraftonline.dev.heroes.api.SkillResult;
import com.herocraftonline.dev.heroes.effects.ExpirableEffect;
import com.herocraftonline.dev.heroes.hero.Hero;
import com.herocraftonline.dev.heroes.skill.Skill;
import com.herocraftonline.dev.heroes.skill.SkillConfigManager;
import com.herocraftonline.dev.heroes.skill.SkillType;
import com.herocraftonline.dev.heroes.skill.TargettedSkill;
import com.herocraftonline.dev.heroes.util.Messaging;
import com.herocraftonline.dev.heroes.util.Setting;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event.Type;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityListener;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerRespawnEvent;

public class SkillJail extends TargettedSkill {
    private String jailText;
    private Map<Player, Location> jailedPlayers = new HashMap<Player, Location>();
    private Set<Location> jailLocations;

    public SkillJail(Heroes plugin) {
        super(plugin, "Jail");
        setDescription("Taunt a player for $1s. If that player dies while taunted, they go to Jail. R:$2");
        setUsage("/skill jail");
        setArgumentRange(0, 0);
        setIdentifiers(new String[]{"skill jail"});

        setTypes(SkillType.HARMFUL, SkillType.TELEPORT);
        registerEvent(Type.PLAYER_RESPAWN, new RespawnListener(), Priority.Highest);
        registerEvent(Type.ENTITY_DAMAGE, new JailListener(), Priority.Monitor);
    }

    @Override
    public String getDescription(Hero hero) {
        long duration = (long) (SkillConfigManager.getUseSetting(hero, this, Setting.DURATION.node(), 60000, false) +
                (SkillConfigManager.getUseSetting(hero, this, "duration-increase", 0.0, false) * hero.getLevel())) / 1000;
        duration = duration > 0 ? duration : 0;
        int distance = (int) (SkillConfigManager.getUseSetting(hero, this, Setting.MAX_DISTANCE.node(), 15, false) +
                (SkillConfigManager.getUseSetting(hero, this, Setting.MAX_DISTANCE_INCREASE.node(), 0.0, false) * hero.getLevel()));
        distance = distance > 0 ? distance : 0;
        String description = getDescription().replace("$1", duration + "").replace("$2", distance + "");
        
        //COOLDOWN
        int cooldown = (SkillConfigManager.getUseSetting(hero, this, Setting.COOLDOWN.node(), 0, false)
                - SkillConfigManager.getUseSetting(hero, this, Setting.COOLDOWN_REDUCE.node(), 0, false) * hero.getLevel()) / 1000;
        if (cooldown > 0) {
            description += " CD:" + cooldown + "s";
        }
        
        //MANA
        int mana = SkillConfigManager.getUseSetting(hero, this, Setting.MANA.node(), 10, false)
                - (SkillConfigManager.getUseSetting(hero, this, Setting.MANA_REDUCE.node(), 0, false) * hero.getLevel());
        if (mana > 0) {
            description += " M:" + mana;
        }
        
        //HEALTH_COST
        int healthCost = SkillConfigManager.getUseSetting(hero, this, Setting.HEALTH_COST, 0, false) - 
                (SkillConfigManager.getUseSetting(hero, this, Setting.HEALTH_COST_REDUCE, mana, true) * hero.getLevel());
        if (healthCost > 0) {
            description += " HP:" + healthCost;
        }
        
        //STAMINA
        int staminaCost = SkillConfigManager.getUseSetting(hero, this, Setting.STAMINA.node(), 0, false)
                - (SkillConfigManager.getUseSetting(hero, this, Setting.STAMINA_REDUCE.node(), 0, false) * hero.getLevel());
        if (staminaCost > 0) {
            description += " FP:" + staminaCost;
        }
        
        //DELAY
        int delay = SkillConfigManager.getUseSetting(hero, this, Setting.DELAY.node(), 0, false) / 1000;
        if (delay > 0) {
            description += " W:" + delay + "s";
        }
        
        //EXP
        int exp = SkillConfigManager.getUseSetting(hero, this, Setting.EXP.node(), 0, false);
        if (exp > 0) {
            description += " XP:" + exp;
        }
        return description;
    }

    @Override
    public ConfigurationSection getDefaultConfig() {
        ConfigurationSection node = super.getDefaultConfig();
        String worldName = plugin.getServer().getWorlds().get(0).getName();
        node.set("jail-text", "%target% was jailed!");
        node.set("default", worldName + ":0:65:0");
        node.set(Setting.MAX_DISTANCE.node(), 25);
        node.set(Setting.MAX_DISTANCE_INCREASE.node(), 0);
        node.set(Setting.DURATION.node(), 60000);
        node.set("duration-increase", 0);
        return node;
    }
    
    @Override
    public void init() {
        super.init();
        jailLocations = new HashSet<Location>();
        jailText = SkillConfigManager.getRaw(this, "jail-text",  "%target% was jailed!").replace("%target%", "$1");
        for (String n : SkillConfigManager.getRawKeys(this, null)) {
            String retrievedNode = SkillConfigManager.getRaw(this, n, (String) null);
            if (retrievedNode != null) {
                String[] splitArg = retrievedNode.split(":");
                if (retrievedNode != null && splitArg.length == 4) {
                    World world = plugin.getServer().getWorld(splitArg[0]);
                    jailLocations.add(new Location(world, Double.parseDouble(splitArg[1]), Double.parseDouble(splitArg[2]), Double.parseDouble(splitArg[3])));
                }
            }
        }
        
    }

    @Override
    public SkillResult use(Hero hero, LivingEntity target, String[] args) {
        if (jailLocations.isEmpty()) {
            Messaging.send(hero.getPlayer(), "There are no jails setup yet.");
            return SkillResult.INVALID_TARGET_NO_MSG;
        }
        Player player = hero.getPlayer();
        if (target.equals(player)) {
            return SkillResult.INVALID_TARGET;
        } else if (target instanceof Player && damageCheck((Player) target, player)) {
            Hero tHero = plugin.getHeroManager().getHero((Player) target);
            long duration = (long) (SkillConfigManager.getUseSetting(hero, this, Setting.DURATION.node(), 60000, false) +
                    (SkillConfigManager.getUseSetting(hero, this, "duration-increase", 0.0, false) * hero.getLevel()));
            duration = duration > 0 ? duration : 0;
            tHero.addEffect(new JailEffect(this, duration));
            broadcastExecuteText(hero, target);
        }
        
        return SkillResult.NORMAL;
    }
    
    public class JailEffect extends ExpirableEffect {
        public JailEffect(Skill skill, long duration) {
            super(skill, "Jail", duration);
        }
    }
    
    public class JailListener extends EntityListener {
        @Override
        public void onEntityDamage(EntityDamageEvent event) {
            if (event.isCancelled() || !(event.getEntity() instanceof Player) || event.getDamage() == 0 || jailLocations.isEmpty() ||
                    event.getDamage() < plugin.getHeroManager().getHero((Player) event.getEntity()).getHealth()) {
                return;
            }
            Player player = (Player) event.getEntity();
            Hero hero = plugin.getHeroManager().getHero(player);
            if (hero.hasEffect("Jail")) {
                broadcast(player.getLocation(),jailText,player.getDisplayName());
                Location tempLocation = null;
                for (Location l : jailLocations) {
                    if (tempLocation == null || tempLocation.distanceSquared(player.getLocation()) > l.distanceSquared(player.getLocation())) {
                        tempLocation = l;
                    }
                }
                jailedPlayers.put(player, tempLocation);
            }
        }
    }
    
    public class RespawnListener extends PlayerListener {
        @Override
        public void onPlayerRespawn(final PlayerRespawnEvent event) {
            if (!jailedPlayers.isEmpty() && jailedPlayers.containsKey(event.getPlayer())) {
                event.setRespawnLocation(jailedPlayers.get(event.getPlayer()));
                jailedPlayers.remove(event.getPlayer());
            }
        }
    }

}