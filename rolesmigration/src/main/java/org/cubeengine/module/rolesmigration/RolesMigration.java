/**
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
import de.cubeisland.engine.modularity.asm.marker.ModuleInfo;
import de.cubeisland.engine.modularity.core.Maybe;
import de.cubeisland.engine.modularity.core.Module;
import de.cubeisland.engine.modularity.core.marker.Disable;
import de.cubeisland.engine.modularity.core.marker.Enable;
import org.cubeengine.butler.parametric.Command;
import org.cubeengine.libcube.service.database.Database;
import org.cubeengine.libcube.service.event.EventManager;
import org.cubeengine.libcube.service.filesystem.ModuleConfig;
import org.cubeengine.libcube.service.i18n.I18n;
import org.cubeengine.module.roles.Roles;
import org.cubeengine.module.roles.data.PermissionData;
import org.cubeengine.module.roles.service.subject.UserSubject;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.filter.Getter;
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
import java.util.Optional;
import java.util.UUID;

import javax.inject.Inject;

@ModuleInfo(name = "Bukkit Migration", description = "Migrate your data")
public class RolesMigration extends Module
{
    @ModuleConfig private RolesMigrationConfig config;
    @Inject private Database db;
    @Inject private Log logger;
    @Inject private I18n i18n;
    @Inject private EventManager em;
    @Inject private PluginContainer plugin;

    @Inject Maybe<Roles> roles;
    private Map<UUID, List<String>> roleMap = new HashMap<>();

    @Enable
    public void onEnable() throws SQLException
    {
        if (roles.isAvailable())
        {
            Statement stmt = db.getConnection().createStatement();
            ResultSet rs = stmt.executeQuery(
                  "SELECT u.UUIDleast, u.UUIDmost, r.roleName "
                    + "FROM " + config.prefix + "user as u, "
                    + config.prefix + "roles as r "
                    + "WHERE u.`key` = r.userId");

            while (rs.next())
            {
                UUID uuid = new UUID(rs.getLong("UUIDleast"), rs.getLong("UUIDmost"));
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

    @Disable
    public void onDisable()
    {

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
        data.getParents().addAll(oldroles);
        player.offer(data);
        UserSubject subject = roles.value().getService().getUserSubjects().get(player.getIdentifier());
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
