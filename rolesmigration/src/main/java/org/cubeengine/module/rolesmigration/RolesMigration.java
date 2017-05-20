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
import de.cubeisland.engine.modularity.core.Module;
import de.cubeisland.engine.modularity.core.marker.Disable;
import de.cubeisland.engine.modularity.core.marker.Enable;
import org.cubeengine.butler.parametric.Command;
import org.cubeengine.libcube.service.database.Database;
import org.cubeengine.libcube.service.filesystem.ModuleConfig;
import org.cubeengine.libcube.service.i18n.I18n;
import org.cubeengine.libcube.util.ConfirmManager;
import org.cubeengine.module.roles.Roles;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.event.EventManager;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.text.Text;

import java.util.Optional;

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

    @Inject Optional<Roles> roles;

    @Enable
    public void onEnable()
    {
        em.registerListeners(plugin, this);
    }

    @Disable
    public void onDisable()
    {

    }

    @Listener
    public void onLogin(ClientConnectionEvent.Join event)
    {

    }

    @Command(desc = "Cleanup all the mess")
    public void cleanUpRolesBukkitData(CommandSource ctx)
    {
        ConfirmManager.requestConfirmation(i18n, Text.of("DROP all old data?"), ctx, () -> {
            // TODO drop all the old tables

        });
    }

}
