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
package org.cubeengine.module.dbmigration;

import static org.cubeengine.module.conomy.storage.TableAccount.TABLE_ACCOUNT;
import static org.cubeengine.module.conomy.storage.TableBalance.TABLE_BALANCE;
import static org.cubeengine.module.locker.storage.TableAccessList.TABLE_ACCESSLIST;
import static org.cubeengine.module.locker.storage.TableLockLocations.TABLE_LOCK_LOCATIONS;
import static org.cubeengine.module.locker.storage.TableLocks.TABLE_LOCKS;
import static org.cubeengine.module.vote.storage.TableVote.TABLE_VOTE;

import de.cubeisland.engine.logscribe.Log;
import de.cubeisland.engine.modularity.asm.marker.ModuleInfo;
import de.cubeisland.engine.modularity.core.Maybe;
import de.cubeisland.engine.modularity.core.Module;
import de.cubeisland.engine.modularity.core.marker.Disable;
import de.cubeisland.engine.modularity.core.marker.Enable;
import org.cubeengine.butler.parametric.Command;
import org.cubeengine.butler.parametric.Flag;
import org.cubeengine.libcube.service.command.CommandManager;
import org.cubeengine.libcube.service.database.Database;
import org.cubeengine.libcube.service.database.mysql.MySQLDatabaseConfiguration;
import org.cubeengine.libcube.service.filesystem.ModuleConfig;
import org.cubeengine.libcube.service.i18n.I18n;
import org.cubeengine.module.conomy.Conomy;
import org.cubeengine.module.conomy.storage.TableBalance;
import org.cubeengine.module.locker.Locker;
import org.cubeengine.module.vote.Vote;
import org.spongepowered.api.command.CommandSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

@ModuleInfo(name = "DBBukkitMigration", description = "Migrate your data")
public class DbMigration extends Module
{
    @ModuleConfig private MigrationConfig config;
    @Inject private Database db;
    @Inject private CommandManager cm;
    @Inject private Log logger;
    @Inject private I18n i18n;

    @Inject Maybe<Conomy> conomy;
    @Inject Maybe<Locker> locker;
    @Inject Maybe<Vote> vote;

    @Enable
    public void onEnable()
    {
        cm.addCommands(this, this);
    }

    @Disable
    public void onDisable()
    {
    }

    @Command(desc = "Migrates old Bukkit Data")
    public void migrateBukkitData(CommandSource ctx, @Flag boolean keepOld) throws SQLException
    {
        int cnt;
        String mainPrefix = ((MySQLDatabaseConfiguration) db.getDatabaseConfig()).tablePrefix;
        Connection conn = db.getConnection();
        Statement stmt = conn.createStatement();
        // MySQL Tables:
        // OLD user - no longer needed - but we need the mappings `key` to UUID
        Map<Long, UUID> userMap = new HashMap<>();

        ResultSet rs = stmt.executeQuery("SELECT `key`, UUIDleast, UUIDmost FROM `" + config.prefix + "user`");
        while (rs.next())
        {
            long uid = rs.getLong("key");
            UUID uuid = new UUID(rs.getLong("UUIDmost"), rs.getLong("UUIDleast"));
            userMap.put(uid, uuid);
        }

        logger.info("Users in user table: {}", userMap.size());
        String tableUserUUIDs = config.prefix + "user_uuids";
        stmt.execute("CREATE TABLE IF NOT EXISTS " + tableUserUUIDs + " ("
                + "ID NUMERIC,"
                + "UUID VARCHAR(64)"
                + ")");
        stmt.execute("TRUNCATE " + tableUserUUIDs);
        batchInsertUUIDMap(conn, userMap, tableUserUUIDs);
        // tableUserUUIDs is now filled with old ID => UUID for users

        // OLD worlds - no longer needed - but we need the mappings `key` to UUID
        Map<Long, UUID> worldMap = new HashMap<>();

        rs = stmt.executeQuery("SELECT `key`, UUIDleast, UUIDmost FROM `" + config.prefix + "worlds`");
        while (rs.next())
        {
            long uid = rs.getLong("key");
            UUID uuid = new UUID(rs.getLong("UUIDmost"), rs.getLong("UUIDleast"));
            worldMap.put(uid, uuid);
        }

        logger.info("Worlds in world table: {}", worldMap.size());
        String tableWorldUUIDs = config.prefix + "world_uuids";
        stmt.execute("CREATE TABLE IF NOT EXISTS " + tableWorldUUIDs + " ("
                + "ID NUMERIC,"
                + "UUID VARCHAR(64) )");

        stmt.execute("TRUNCATE " + tableWorldUUIDs);
        batchInsertUUIDMap(conn, worldMap, tableWorldUUIDs);
        // tableWorldUUIDs is now filled with old ID => UUID for worlds

        // commence migration...

        // OLD cube_account_access: empty

        // OLD accounts: key, user_id(user table), name, value, mask (1=hidden 2=needsinvite)
        // NEW conomy_account id(uuid), name, mask (same + 4=uuid for players)
        // NEW conomy_balance id(uuid), currency, context, balance
        logger.info("migrate topl...");
        if (conomy.isAvailable())
        {
            // INFO: This does not handle bank accounts

            if (!keepOld) // Clear current data?
            {
                stmt.execute("DELETE FROM " + mainPrefix + TABLE_ACCOUNT.getName());
            }
            // Migrate Player Accounts
            cnt = stmt.executeUpdate("INSERT INTO `" + mainPrefix + TABLE_ACCOUNT.getName() + "` "
                    + "(id, name, HIDDEN, INVITE, IS_UUID)"
                    + " SELECT u.UUID, ou.lastname, ac.mask & 1 = 1, ac.mask & 2 = 2, true"
                    + " FROM " + tableUserUUIDs + " as u, "
                    + config.prefix + "user as ou,"
                    + config.prefix + "accounts as ac "
                    + "WHERE ac.user_id = ou.`key`"
                    + "AND ac.user_id = u.ID");
            logger.info(cnt + " accounts");
            // Migrate Player Account balance
            String defCurrency = conomy.value().getConfig().defaultCurrency;
            cnt = stmt.executeUpdate("INSERT INTO `" + mainPrefix + TABLE_BALANCE.getName() + "` "
                    + "(id, currency, context, balance)"
                    + " SELECT u.UUID, '" + defCurrency +"', 'global|', ac.value"
                    + " FROM " + tableUserUUIDs + " as u, "
                    + config.prefix + "accounts as ac "
                    + "WHERE ac.user_id = u.ID");
            logger.info(cnt + " balances");
            // Done!
        }

        // OLD basicuser: not needed
        // OLD holiday: empty
        // OLD ignorelist: empty
        // OLD kits: not needed?

        // OLD locks
        // NEW locker_locks
        // OLD lockaccesslist
        // NEW locker_accesslist
        // OLD locklocation
        // NEW locker_location

        logger.info("migrate locker...");
        if (locker.isAvailable())
        {
            if (!keepOld)
            {
                stmt.execute("DELETE FROM " + mainPrefix +TABLE_ACCESSLIST.getName());
                stmt.execute("DELETE FROM " + mainPrefix +TABLE_LOCK_LOCATIONS.getName());
                stmt.execute("DELETE FROM " + mainPrefix +TABLE_LOCKS.getName());
            }
            // Copy Locks
            stmt.execute("ALTER TABLE `" + mainPrefix +TABLE_LOCKS.getName() + "` ADD (OLD_ID NUMERIC)");
            cnt = stmt.executeUpdate("INSERT INTO `" + mainPrefix +TABLE_LOCKS.getName() + "` "
                    + "(owner_id, flags, type, lock_type, password, entity_uuid, last_access, created, OLD_ID) "
                    + "SELECT l.ID, u.UUID, l.flags, l.type, l.lock_type, l.password, NULL, l.last_access, l.created, l.id "
                    + "FROM " + tableUserUUIDs + " as u, "
                    + config.prefix +"locks as l "
                    + "WHERE l.owner_id = u.id "
                    + "AND l.entity_uid_least IS NULL");
            logger.info(cnt + " locks");

            // Copy Lock Locations
            cnt = stmt.executeUpdate("INSERT INTO `" + mainPrefix +TABLE_LOCK_LOCATIONS.getName() + "` "
                    + "(world_id, x,y,z, chunkX, chunkZ, lock_id) "
                    + "SELECT w.UUID, ll.x, ll.y, ll.z, ll.chunkX, ll.chunkZ, l.id "
                    + "FROM " + tableWorldUUIDs + " as w,"
                    + config.prefix + "locklocation as ll, "
                    + TABLE_LOCKS.getName() + " as l "
                    + "WHERE w.ID = ll.world_id "
                    + "AND ll.lock_id = l.old_id");
            logger.info(cnt + " locklocation");

            // Copy Lock AccessList
            // First global
            cnt = stmt.executeUpdate("INSERT INTO `" + mainPrefix +TABLE_ACCESSLIST.getName() + "` "
                    + "(user_id, lock_id, level, owner_id) "
                    + "SELECT u1.UUID, NULL, al.level, u2.UUID "
                    + "FROM " + tableUserUUIDs + " as u1, "
                    +           tableUserUUIDs + " as u2, "
                    + config.prefix + "lockaccesslist as al "
                    + "WHERE u1.ID = al.user_id "
                    + "AND u2.ID = al.owner_id "
                    + "AND al.owner_id IS NOT NULL");
            logger.info(cnt + " global lockaccess");

            // Then single locks
            stmt.execute("INSERT INTO `" + mainPrefix +TABLE_ACCESSLIST.getName() + "` "
                    + "(user_id, lock_id, level, owner_id) "
                    + "SELECT u1.UUID, l.ID, al.level, NULL "
                    + "FROM " + tableUserUUIDs + " as u1, "
                    + mainPrefix +TABLE_LOCKS.getName() + " as l,"
                    + config.prefix + "lockaccesslist as al "
                    + "WHERE u1.ID = al.user_id "
                    + "AND l.OLD_ID = al.lock_id "
                    + "AND al.lock_id IS NOT NULL");
            logger.info(cnt + " block lockaccess");

            stmt.execute("ALTER TABLE `" + mainPrefix +TABLE_LOCKS.getName() + "` DROP COLUMN OLD_ID");
        }

        // OLD mail: not needed
        // OLD namehistory: not needed
        // OLD registry: not needed

        // OLD repairblocks: not needed for us
        // OLD roles:    separate module for that as we cannot use offline custom data yet
        // OLD userdata: separate module for that as we cannot use offline custom data yet
        // OLD userperms:separate module for that as we cannot use offline custom data yet

        // OLD signmarketblocks: TODO toNBT
        // OLD signmarketitem: TODO toNBT

        // OLD teleportinvites: TODO toConfig
        // OLD teleportpoints: TODO toConfig

        // OLD votes
        // NEW votecount
        logger.info("migrate votes...");
        if (vote.isAvailable())
        {
            if (!keepOld)
            {
                stmt.execute("DELETE FROM " + mainPrefix +TABLE_VOTE.getName());
            }
            cnt =stmt.executeUpdate("INSERT INTO " + mainPrefix +TABLE_VOTE.getName() + " "
                    + "(userid, lastvote, voteamount) "
                    + "SELECT u.UUID, v.lastvote, v.voteamount "
                    + "FROM " + tableUserUUIDs + " as u,"
                    + config.prefix + "votes as v "
                    + "WHERE v.userid = u.id");
            logger.info(cnt + " voters");
        }

        logger.info("Migration done!");
    }

    private void batchInsertUUIDMap(Connection conn, Map<Long, UUID> map, String table) throws SQLException
    {
        if (map.size() > 0)
        {
            PreparedStatement pStmt = conn.prepareStatement("INSERT INTO " + table + " VALUES(?, ?)");
            int i = 0;
            for (Map.Entry<Long, UUID> entry : map.entrySet())
            {
                i++;
                pStmt.setLong(1, entry.getKey());
                pStmt.setString(2, entry.getValue().toString());
                pStmt.addBatch();
                if (i % 100 == 0)
                {
                    pStmt.executeBatch();
                }
            }
            pStmt.executeBatch();
        }
    }
}
