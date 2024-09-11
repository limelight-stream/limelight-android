package com.limelight.computers;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import org.json.JSONException;
import org.json.JSONObject;

public class ComputerDatabaseManager {
    private static final String COMPUTER_DB_NAME = "computers4.db";
    private static final String COMPUTER_TABLE_NAME = "Computers";
    private static final String COMPUTER_UUID_COLUMN_NAME = "UUID";
    private static final String COMPUTER_NAME_COLUMN_NAME = "ComputerName";
    private static final String ADDRESSES_COLUMN_NAME = "Addresses";
    private interface AddressFields {
        String LOCAL = "local";
        String REMOTE = "remote";
        String MANUAL = "manual";
        String IPv6 = "ipv6";

        String ADDRESS = "address";
        String PORT = "port";
    }

    private static final String MAC_ADDRESS_COLUMN_NAME = "MacAddress";
    private static final String SERVER_CERT_COLUMN_NAME = "ServerCert";

    private SQLiteDatabase computerDb;

    public ComputerDatabaseManager(Context c) {
        try {
            // Create or open an existing DB
            computerDb = c.openOrCreateDatabase(COMPUTER_DB_NAME, 0, null);
        } catch (SQLiteException e) {
            // Delete the DB and try again
            c.deleteDatabase(COMPUTER_DB_NAME);
            computerDb = c.openOrCreateDatabase(COMPUTER_DB_NAME, 0, null);
        }
        initializeDb(c);
    }

    public void close() {
        computerDb.close();
    }

    private void initializeDb(Context c) {
        // Create tables if they aren't already there
        computerDb.execSQL(String.format((Locale)null,
                "CREATE TABLE IF NOT EXISTS %s(%s TEXT PRIMARY KEY, %s TEXT NOT NULL, %s TEXT NOT NULL, %s TEXT, %s TEXT)",
                COMPUTER_TABLE_NAME, COMPUTER_UUID_COLUMN_NAME, COMPUTER_NAME_COLUMN_NAME,
                ADDRESSES_COLUMN_NAME, MAC_ADDRESS_COLUMN_NAME, SERVER_CERT_COLUMN_NAME));

        // Move all computers from the old DB (if any) to the new one
        List<ComputerDetails> oldComputers = LegacyDatabaseReader.migrateAllComputers(c);
        for (ComputerDetails computer : oldComputers) {
            updateComputer(computer);
        }
        oldComputers = LegacyDatabaseReader2.migrateAllComputers(c);
        for (ComputerDetails computer : oldComputers) {
            updateComputer(computer);
        }
        oldComputers = LegacyDatabaseReader3.migrateAllComputers(c);
        for (ComputerDetails computer : oldComputers) {
            updateComputer(computer);
        }
    }

    public void deleteComputer(ComputerDetails details) {
        computerDb.delete(COMPUTER_TABLE_NAME, COMPUTER_UUID_COLUMN_NAME+"=?", new String[]{details.uuid});
    }

    public static JSONObject tupleToJson(ComputerDetails.AddressTuple tuple) throws JSONException {
        if (tuple == null) {
            return null;
        }

        JSONObject json = new JSONObject();
        json.put(AddressFields.ADDRESS, tuple.address);
        json.put(AddressFields.PORT, tuple.port);

        return json;
    }

    public static ComputerDetails.AddressTuple tupleFromJson(JSONObject json, String name) throws JSONException {
        if (!json.has(name)) {
            return null;
        }

        JSONObject address = json.getJSONObject(name);
        return new ComputerDetails.AddressTuple(
                address.getString(AddressFields.ADDRESS), address.getInt(AddressFields.PORT));
    }

    public boolean updateComputer(ComputerDetails details) {
        ContentValues values = new ContentValues();
        values.put(COMPUTER_UUID_COLUMN_NAME, details.uuid);
        values.put(COMPUTER_NAME_COLUMN_NAME, details.name);

        try {
            JSONObject addresses = new JSONObject();
            addresses.put(AddressFields.LOCAL, tupleToJson(details.localAddress));
            addresses.put(AddressFields.REMOTE, tupleToJson(details.remoteAddress));
            addresses.put(AddressFields.MANUAL, tupleToJson(details.manualAddress));
            addresses.put(AddressFields.IPv6, tupleToJson(details.ipv6Address));
            values.put(ADDRESSES_COLUMN_NAME, addresses.toString());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        values.put(MAC_ADDRESS_COLUMN_NAME, details.macAddress);
        try {
            if (details.serverCert != null) {
                values.put(SERVER_CERT_COLUMN_NAME, details.serverCert.getEncoded());
            }
            else {
                values.put(SERVER_CERT_COLUMN_NAME, (byte[])null);
            }
        } catch (CertificateEncodingException e) {
            values.put(SERVER_CERT_COLUMN_NAME, (byte[])null);
            e.printStackTrace();
        }
        return -1 != computerDb.insertWithOnConflict(COMPUTER_TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private ComputerDetails getComputerFromCursor(Cursor c) {
        ComputerDetails details = new ComputerDetails();

        details.uuid = c.getString(0);
        details.name = c.getString(1);
        try {
            JSONObject addresses = new JSONObject(c.getString(2));
            details.localAddress = tupleFromJson(addresses, AddressFields.LOCAL);
            details.remoteAddress = tupleFromJson(addresses, AddressFields.REMOTE);
            details.manualAddress = tupleFromJson(addresses, AddressFields.MANUAL);
            details.ipv6Address = tupleFromJson(addresses, AddressFields.IPv6);
        } catch (JSONException e) {
            throw new RuntimeException(e);
         }

        // External port is persisted in the remote address field
        if (details.remoteAddress != null) {
            details.externalPort = details.remoteAddress.port;
        }
        else {
            details.externalPort = NvHTTP.DEFAULT_HTTP_PORT;
        }

        details.macAddress = c.getString(3);

        try {
            byte[] derCertData = c.getBlob(4);

            if (derCertData != null) {
                details.serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(derCertData));
            }
        } catch (CertificateException e) {
            e.printStackTrace();
        }

        // This signifies we don't have dynamic state (like pair state)
        details.state = ComputerDetails.State.UNKNOWN;

        return details;
    }

    public List<ComputerDetails> getAllComputers() {
        try (final Cursor c = computerDb.rawQuery("SELECT * FROM "+COMPUTER_TABLE_NAME, null)) {
            LinkedList<ComputerDetails> computerList = new LinkedList<>();
            while (c.moveToNext()) {
                computerList.add(getComputerFromCursor(c));
            }
            return computerList;
        }
    }

    /**
     * Get a computer by name
     * NOTE: It is perfectly valid for multiple computers to have the same name,
     * this function will only return the first one it finds.
     * Consider using getComputerByUUID instead.
     * @param name The name of the computer
     * @see ComputerDatabaseManager#getComputerByUUID(String) for alternative.
     * @return The computer details, or null if no computer with that name exists
     */
    public ComputerDetails getComputerByName(String name) {
        try (final Cursor c = computerDb.query(
                COMPUTER_TABLE_NAME, null, COMPUTER_NAME_COLUMN_NAME+"=?",
                new String[]{ name }, null, null, null)
        ) {
            if (!c.moveToFirst()) {
                // No matching computer
                return null;
            }

            return getComputerFromCursor(c);
        }
    }

    /**
     * Get a computer by UUID
     * @param uuid The UUID of the computer
     * @see ComputerDatabaseManager#getComputerByName(String) for alternative.
     * @return The computer details, or null if no computer with that UUID exists
     */
    public ComputerDetails getComputerByUUID(String uuid) {
        try (final Cursor c = computerDb.query(
                COMPUTER_TABLE_NAME, null, COMPUTER_UUID_COLUMN_NAME+"=?",
                new String[]{ uuid }, null, null, null)
        ) {
            if (!c.moveToFirst()) {
                // No matching computer
                return null;
            }

            return getComputerFromCursor(c);
        }
    }
}
