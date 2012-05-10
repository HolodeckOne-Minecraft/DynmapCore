package org.dynmap;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.dynmap.common.DynmapCommandSender;
import org.dynmap.common.DynmapPlayer;
import org.dynmap.kzedmap.KzedMap;
import org.dynmap.kzedmap.MapTileRenderer;
import org.dynmap.servlet.LoginServlet;

public class WebAuthManager {
    private HashMap<String, String> pwdhash_by_userid = new HashMap<String, String>();
    private HashMap<String, String> pending_registrations = new HashMap<String, String>();
    private String hashsalt;
    private File pfile;
    public static final String WEBAUTHFILE = "webauth.txt";
    private static final String HASHSALT = "$HASH_SALT$";
    private static final String PWDHASH_PREFIX = "hash.";
    private static final String PERM_PREFIX = "perm.";
    private Random rnd = new Random();
    private DynmapCore core;
    
    public WebAuthManager(DynmapCore core) {
        this.core = core;
        pfile = new File(core.getDataFolder(), WEBAUTHFILE);
        if(pfile.canRead()) {
            FileReader rf = null;
            try {
                rf = new FileReader(pfile);
                Properties p = new Properties();
                p.load(rf);
                hashsalt = p.getProperty(HASHSALT);
                for(String k : p.stringPropertyNames()) {
                    if(k.equals(HASHSALT)) {
                        hashsalt = p.getProperty(k);
                    }
                    else if(k.startsWith(PWDHASH_PREFIX)) { /* Load password hashes */
                        pwdhash_by_userid.put(k.substring(PWDHASH_PREFIX.length()).toLowerCase(), p.getProperty(k));
                    }
                }
                
            } catch (IOException iox) {
                Log.severe("Cannot read " + WEBAUTHFILE);
            } finally {
                if(rf != null) { try { rf.close(); } catch (IOException iox) {} }
            }
        }
        if(hashsalt == null) {  /* No hashsalt */
            hashsalt = Long.toHexString(rnd.nextLong());
        }
    }
    public boolean save() {
        boolean success = false;
        FileWriter fw = null;
        try {
            fw = new FileWriter(pfile);
            Properties p = new Properties();
            p.setProperty(HASHSALT, hashsalt);  /* Save salt */
            for(String k : pwdhash_by_userid.keySet()) {
                p.setProperty(PWDHASH_PREFIX + k, pwdhash_by_userid.get(k));
            }
            p.store(fw, "DO NOT EDIT THIS FILE");
            success = true;
        } catch (IOException iox) {
            Log.severe("Error writing " + WEBAUTHFILE);
        } finally {
            if(fw != null) { try { fw.close(); } catch (IOException iox) {} }
        }
        if(success) 
            core.events.trigger("loginupdated", null);
        return success;
    }
    private String makeHash(String pwd) {
        String check = hashsalt + pwd;
        try {
            byte[] checkbytes = check.getBytes("UTF-8");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] rslt = md.digest(checkbytes);
            String rslthash = "";
            for(int i = 0; i < rslt.length; i++) {
                rslthash += String.format("%02X", 0xFF & (int)rslt[i]);
            }
            return rslthash;
        } catch (NoSuchAlgorithmException nsax) {
        } catch (UnsupportedEncodingException uex) {
        }
        return null;
    }
    public boolean checkLogin(String uid, String pwd) {
        uid = uid.toLowerCase();
        if(uid.equals(LoginServlet.USERID_GUEST)) {
            return true;
        }
        String hash = pwdhash_by_userid.get(uid);
        if(hash == null) {
            return false;
        }
        if(core.getServer().isPlayerBanned(uid)) {
            return false;
        }
        String checkhash = makeHash(pwd);
        return hash.equals(checkhash);
    }
    public boolean registerLogin(String uid, String pwd, String passcode) {
        uid = uid.toLowerCase();
        if(uid.equals(LoginServlet.USERID_GUEST)) {
            return false;
        }
        if(core.getServer().isPlayerBanned(uid)) {
            return false;
        }
        passcode = passcode.toLowerCase();
        String kcode = pending_registrations.remove(uid);
        if(kcode == null) {
            return false;
        }
        if(!kcode.equals(passcode)) {
            return false;
        }
        String hash = makeHash(pwd);
        pwdhash_by_userid.put(uid, hash);
        return save();
    }
    public boolean unregisterLogin(String uid) {
        if(uid.equals(LoginServlet.USERID_GUEST)) {
            return true;
        }
        uid = uid.toLowerCase();
        pwdhash_by_userid.remove(uid);
        return save();
    }
    public boolean isRegistered(String uid) {
        if(uid.equals(LoginServlet.USERID_GUEST)) {
            return false;
        }
        uid = uid.toLowerCase();
        return pwdhash_by_userid.containsKey(uid);
    }
    boolean processCompletedRegister(String uid, String pc, String hash) {
        uid = uid.toLowerCase();
        if(uid.equals(LoginServlet.USERID_GUEST)) {
            return false;
        }
        if(core.getServer().isPlayerBanned(uid)) {
            return false;
        }
        String kcode = pending_registrations.remove(uid);
        if(kcode == null) {
            return false;
        }
        pc = pc.toLowerCase();
        if(!kcode.equals(pc)) {
            return false;
        }
        pwdhash_by_userid.put(uid, hash);
        return save();
    }
    public boolean processWebRegisterCommand(DynmapCore core, DynmapCommandSender sender, DynmapPlayer player, String[] args) {
        String uid = null;
        boolean other = false;
        if(args.length > 1) {
            if(!core.checkPlayerPermission(sender, "webregister.other")) {
                sender.sendMessage("Not authorized to set web login information for other players");
                return true;
            }
            uid = args[1];
            other = true;
        }
        else if (player == null) {   /* Console? */
            sender.sendMessage("Must provide user ID to register web login");
            return true;
        }
        else {
            uid = player.getName();
        }
        String regkey = String.format("%04d-%04d", rnd.nextInt(10000), rnd.nextInt(10000));
        pending_registrations.put(uid.toLowerCase(), regkey.toLowerCase());
        sender.sendMessage("Registration pending for user ID: " + uid);
        sender.sendMessage("Registration code: " + regkey);
        sender.sendMessage("Enter ID and code on registration web page (login.html) to complete registration");
        if(other) {
            DynmapPlayer p = core.getServer().getPlayer(uid);
            if(p != null) {
                p.sendMessage("The registration of your account for web access has been started.");
                p.sendMessage("To complete the process, access the Login page on the Dynmap map");
                p.sendMessage("Registration code: " + regkey);
                p.sendMessage("The user ID must match your account ID, but the password should NOT be the same.");
            }
        }
        core.events.trigger("loginupdated", null);
        
        return true;
    }
    String getLoginPHP() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?php\n");
        sb.append("$pwdsalt = '").append(hashsalt).append("';\n");
        /* Create password hash */
        sb.append("$pwdhash = array(\n");
        for(String uid : pwdhash_by_userid.keySet()) {
            sb.append("  \'").append(esc(uid)).append("\' => \'").append(esc(pwdhash_by_userid.get(uid))).append("\',\n");
        }
        sb.append(");\n");
        /* Create registration table */
        sb.append("$pendingreg = array(\n");
        for(String uid : pending_registrations.keySet()) {
            sb.append("  \'").append(esc(uid)).append("\' => \'").append(esc(pending_registrations.get(uid))).append("\',\n");
        }
        sb.append(");\n");
        sb.append("?>\n");
        
        return sb.toString();
    }
    
    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if(c == '\\')
                sb.append("\\\\");
            else if(c == '\'')
                sb.append("\\\'");
            else
                sb.append(c);
        }
        return sb.toString();
    }
    
    String getAccessPHP() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?php\n");
        
        ArrayList<String> mid = new ArrayList<String>();
        /* Create world access list */
        sb.append("$worldaccess = array(\n");
        for(DynmapWorld w : core.getMapManager().getWorlds()) {
            if(w.isProtected()) {
                String perm = "world." + w.getName();
                sb.append("  \'").append(esc(w.getName())).append("\' => \'");
                for(String uid : pwdhash_by_userid.keySet()) {
                    if(core.getServer().checkPlayerPermission(uid, perm)) {
                        sb.append("[").append(esc(uid)).append("]");
                    }
                }
                sb.append("\',\n");
            }
            for(MapType mt : w.maps) {
                if(mt instanceof KzedMap) {
                    KzedMap kmt = (KzedMap)mt;
                    for(MapTileRenderer tr : kmt.renderers) {
                        if(tr.isProtected()) {
                            mid.add(w.getName() + "." + tr.getPrefix());
                        }
                    }
                }
                else if(mt.isProtected()) {
                    mid.add(w.getName() + "." + mt.getPrefix());
                }
            }
        }
        sb.append(");\n");

        /* Create map access list */
        sb.append("$mapaccess = array(\n");
        for(String id : mid) {
            String perm = "map." + id;
            sb.append("  \'").append(esc(id)).append("\' => \'");
            for(String uid : pwdhash_by_userid.keySet()) {
                if(core.getServer().checkPlayerPermission(uid, perm)) {
                    sb.append("[").append(esc(uid)).append("]");
                }
            }
            sb.append("\',\n");
        }
        sb.append(");\n");

        String perm = "playermarkers.seeall";
        sb.append("$seeallmarkers = \'");
        for(String uid : pwdhash_by_userid.keySet()) {
            if(core.getServer().checkPlayerPermission(uid, perm)) {
                sb.append("[").append(esc(uid)).append("]");
            }
        }
        sb.append("\';\n");

        String p = core.getTilesFolder().getAbsolutePath();
        if(!p.endsWith("/"))
            p += "/";
        sb.append("$tilespath = \'");
        sb.append(esc(p));
        sb.append("\';\n");

        File wpath = new File(core.getWebPath());
        p = wpath.getAbsolutePath();
        if(!p.endsWith("/"))
            p += "/";
        sb.append("$webpath = \'");
        sb.append(esc(p));
        sb.append("\';\n");

        sb.append("?>\n");
        
        return sb.toString();
    }

    boolean pendingRegisters() {
        return (pending_registrations.size() > 0);
    }
    Set<String> getUserIDs() {
        HashSet<String> lst = new HashSet<String>();
        lst.addAll(pwdhash_by_userid.keySet());
        lst.addAll(pending_registrations.keySet());
        return lst;
    }
}
