/*
 * This file is part of CubeEngine.
 * CubeEngine is licensed under the GNU General Public License Version 3.
 *
 * CubeEngine is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * CubeEngine is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with CubeEngine.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.cubeengine.module.rolesmigration;

import de.cubeisland.engine.logscribe.Log;
import org.cubeengine.butler.parametric.Command;
import org.cubeengine.libcube.CubeEngineModule;
import org.cubeengine.libcube.ModuleManager;
import org.cubeengine.libcube.service.command.CommandManager;
import org.cubeengine.libcube.service.database.Database;
import org.cubeengine.libcube.service.event.EventManager;
import org.cubeengine.libcube.service.filesystem.ModuleConfig;
import org.cubeengine.libcube.service.i18n.I18n;
import org.cubeengine.module.roles.Roles;
import org.cubeengine.module.roles.data.PermissionData;
import org.cubeengine.module.roles.service.subject.UserSubject;
import org.cubeengine.processor.Dependency;
import org.cubeengine.processor.Module;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.filter.Getter;
import org.spongepowered.api.event.game.state.GamePostInitializationEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.service.whitelist.WhitelistService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@Module(id = "rolebukkitmigration", name = "RolesBukkitMigration", version = "1.0.0",
        description = "Migrate your data",
        dependencies = {@Dependency("cubeengine-core"), @Dependency("cubeengine-roles")},
        url = "http://cubeengine.org",
        authors = {"Anselm 'Faithcaio' Brehme", "Phillip Schichtel"})
public class RolesMigration extends CubeEngineModule
{
    @ModuleConfig private RolesMigrationConfig config;
    @Inject private Database db;
    private Log logger;
    @Inject private I18n i18n;
    @Inject private EventManager em;
    @Inject private PluginContainer plugin;
    @Inject private CommandManager cm;
    @Inject private ModuleManager mm;

    private Map<UUID, List<String>> roleMap = new HashMap<>();
    private Roles roles;

    @Listener
    public void onEnable(GamePostInitializationEvent event) throws SQLException
    {
        this.logger = mm.getLoggerFor(RolesMigration.class);
        this.roles = ((Roles) mm.getModule(Roles.class));
        cm.addCommands(this, this);
        if (roles != null)
        {
            Statement stmt = db.getConnection().createStatement();
            ResultSet rs = stmt.executeQuery(
                  "SELECT u.UUIDleast, u.UUIDmost, r.roleName "
                    + "FROM " + config.prefix + "user as u, "
                    + config.prefix + "roles as r "
                    + "WHERE u.`key` = r.userId");

            while (rs.next())
            {
                UUID uuid = new UUID(rs.getLong("UUIDmost"), rs.getLong("UUIDleast"));
                String role = rs.getString("rolename");
                List<String> roleList = roleMap.getOrDefault(uuid, new ArrayList<>());
                roleList.add(role);
                roleMap.putIfAbsent(uuid, roleList);
            }

            logger.info("Loaded {} players with their roles.", roleMap.size());
            em.registerListener(RolesMigration.class, this);
        }
        else
        {
            logger.warn("Roles Module not found. Migration cannot happen!");
        }
    }

    @Listener
    public void onLogin(ClientConnectionEvent.Join event, @Getter("getTargetEntity") Player player)
    {
        List<String> oldroles = roleMap.get(player.getUniqueId());
        if (oldroles == null || oldroles.isEmpty())
        {
            return;
        }
        PermissionData data = player.get(PermissionData.class).orElse(new PermissionData(new ArrayList<>(), new HashMap<>(), new HashMap<>()));

        logger.info("Adding Roles to {}", player.getName());
        for (String oldrole : oldroles)
        {
            logger.info(" - {}", oldrole);
        }

        data.getParents().addAll(oldroles);
        player.offer(data);
        UserSubject subject = roles.getService().getUserSubjects().get(player.getIdentifier());
        subject.reload();
    }

    @Command(desc = "Adds all players with non-default roles to the whitelist")
    public void whitelistRoles(CommandSource ctx)
    {
        WhitelistService ws = Sponge.getServiceManager().provideUnchecked(WhitelistService.class);
        for (UUID uuid : roleMap.keySet())
        {
            ws.addProfile(GameProfile.of(uuid));
        }
    }

}
