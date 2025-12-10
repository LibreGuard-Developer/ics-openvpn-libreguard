/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import android.os.Build;
import androidx.core.util.Pair;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;

import de.blinkt.openvpn.VpnProfile;

//! Openvpn Config FIle Parser, probably not 100% accurate but close enough

// And remember, this is valid :)
// --<foo>
// bar
// </foo>
public class ConfigParser {

    // This value was originally "converted Profile"
    public static final String CONVERTED_PROFILE = "LibreGuard";
    final String[] unsupportedOptions = {"config",
            "tls-server"

    };
    // Ignore all scripts
    // in most cases these won't work and user who wish to execute scripts will
    // figure out themselves
    private final String[] ignoreOptions = {"tls-client",
            "allow-recursive-routing",
            "askpass",
            "auth-nocache",
            "up",
            "down",
            "route-up",
            "ipchange",
            "route-pre-down",
            "auth-user-pass-verify",
            "block-outside-dns",
            "client-cert-not-required",
            "dhcp-release",
            "dhcp-renew",
            "dh",
            "group",
            "ip-win32",
            "ifconfig-nowarn",
            "management-hold",
            "management",
            "management-client",
            "management-query-remote",
            "management-query-passwords",
            "management-query-proxy",
            "management-external-key",
            "management-forget-disconnect",
            "management-signal",
            "management-log-cache",
            "management-up-down",
            "management-client-user",
            "management-client-group",
            "pause-exit",
            "preresolve",
            "plugin",
            "machine-readable-output",
            "persist-key",
            "push",
            "register-dns",
            "route-delay",
            "route-gateway",
            "route-metric",
            "route-method",
            "status",
            "script-security",
            "show-net-up",
            "suppress-timestamps",
            "tap-sleep",
            "tmp-dir",
            "tun-ipv6",
            "topology",
            "user",
            "win-sys",
    };
    private final String[][] ignoreOptionsWithArg =
            {
                    {"setenv", "IV_GUI_VER"},
                    {"setenv", "IV_SSO"},
                    {"setenv", "IV_PLAT_VER"},
                    {"setenv", "IV_OPENVPN_GUI_VERSION"},
                    {"engine", "dynamic"},
                    {"setenv", "CLIENT_CERT"},
                    {"resolv-retry", "60"}
            };
    private final String[] connectionOptions = {
            "local",
            "remote",
            "float",
            "port",
            "connect-retry",
            "connect-timeout",
            "connect-retry-max",
            "link-mtu",
            "tun-mtu",
            "tun-mtu-extra",
            "fragment",
            "mtu-disc",
            "local-port",
            "remote-port",
            "bind",
            "nobind",
            "proto",
            "http-proxy",
            "http-proxy-retry",
            "http-proxy-timeout",
            "http-proxy-option",
            "socks-proxy",
            "socks-proxy-retry",
            "http-proxy-user-pass",
            "explicit-exit-notify",
    };
    private HashSet<String>  connectionOptionsSet = new HashSet<>(Arrays.asList(connectionOptions));

    private HashMap<String, Vector<Vector<String>>> options = new HashMap<>();
    private HashMap<String, Vector<String>> meta = new HashMap<String, Vector<String>>();
    private String auth_user_pass_file;

    static public void useEmbbedUserAuth(VpnProfile np, String inlinedata) {
        String data = VpnProfile.getEmbeddedContent(inlinedata);
        String[] parts = data.split("\n");
        if (parts.length >= 2) {
            np.mUsername = parts[0];
            np.mPassword = parts[1];
        }
    }

    static public void useEmbbedHttpAuth(Connection c, String inlinedata) {
        String data = VpnProfile.getEmbeddedContent(inlinedata);
        String[] parts = data.split("\n");
        if (parts.length >= 2) {
            c.mProxyAuthUser = parts[0];
            c.mProxyAuthPassword = parts[1];
            c.mUseProxyAuth = true;
        }
    }

    public void parseConfig(Reader reader) throws IOException, ConfigParseError {

        HashMap<String, String> optionAliases = new HashMap<>();
        optionAliases.put("server-poll-timeout", "timeout-connect");

        BufferedReader br = new BufferedReader(reader);

        int lineno = 0;
        try {
            while (true) {
                String line = br.readLine();
                lineno++;
                if (line == null)
                    break;

                if (lineno == 1) {
                    if ((line.startsWith("PK\003\004")
                            || (line.startsWith("PK\007\008")))) {
                        throw new ConfigParseError("Input looks like a ZIP Archive. Import is only possible for OpenVPN config files (.ovpn/.conf)");
                    }
                    if (line.startsWith("\uFEFF")) {
                        line = line.substring(1);
                    }
                }

                // Check for OpenVPN Access Server Meta information
                if (line.startsWith("# OVPN_ACCESS_SERVER_")) {
                    Vector<String> metaarg = parsemeta(line);
                    meta.put(metaarg.get(0), metaarg);
                    continue;
                }
                Vector<String> args = parseline(line);

                if (args.size() == 0)
                    continue;


                if (args.get(0).startsWith("--"))
                    args.set(0, args.get(0).substring(2));

                checkinlinefile(args, br);

                String optionname = args.get(0);
                if (optionAliases.get(optionname) != null)
                    optionname = optionAliases.get(optionname);

                if (!options.containsKey(optionname)) {
                    options.put(optionname, new Vector<Vector<String>>());
                }
                options.get(optionname).add(args);
            }
        } catch (java.lang.OutOfMemoryError memoryError) {
            throw new ConfigParseError("File too large to parse: " + memoryError.getLocalizedMessage());
        }
    }

    private Vector<String> parsemeta(String line) {
        String meta = line.split("#\\sOVPN_ACCESS_SERVER_", 2)[1];
        String[] parts = meta.split("=", 2);
        Vector<String> rval = new Vector<String>();
        Collections.addAll(rval, parts);
        return rval;

    }

    private void checkinlinefile(Vector<String> args, BufferedReader br) throws IOException, ConfigParseError {
        String arg0 = args.get(0).trim();
        // CHeck for <foo>
        if (arg0.startsWith("<") && arg0.endsWith(">")) {
            String argname = arg0.substring(1, arg0.length() - 1);
            String inlinefile = VpnProfile.INLINE_TAG;

            String endtag = String.format("</%s>", argname);
            do {
                String line = br.readLine();
                if (line == null) {
                    throw new ConfigParseError(String.format("No endtag </%s> for starttag <%s> found", argname, argname));
                }
                if (line.trim().equals(endtag))
                    break;
                else {
                    inlinefile += line;
                    inlinefile += "\n";
                }
            } while (true);

            if (inlinefile.endsWith("\n"))
                inlinefile = inlinefile.substring(0, inlinefile.length() - 1);

            args.clear();
            args.add(argname);
            args.add(inlinefile);
        }

    }

    public String getAuthUserPassFile() {
        return auth_user_pass_file;
    }

    private boolean space(char c) {
        // I really hope nobody is using zero bytes inside his/her config file
        // to sperate parameter but here we go:
        return Character.isWhitespace(c) || c == '\0';

    }

    // adapted openvpn's parse function to java
    private Vector<String> parseline(String line) throws ConfigParseError {
        Vector<String> parameters = new Vector<String>();

        if (line.length() == 0)
            return parameters;


        linestate state = linestate.initial;
        boolean backslash = false;
        char out = 0;

        int pos = 0;
        String currentarg = "";

        do {
            // Emulate the c parsing ...
            char in;
            if (pos < line.length())
                in = line.charAt(pos);
            else
                in = '\0';

            if (!backslash && in == '\\' && state != linestate.readin_single_quote) {
                backslash = true;
            } else {
                if (state == linestate.initial) {
                    if (!space(in)) {
                        if (in == ';' || in == '#') /* comment */
                            break;
                        if (!backslash && in == '\"')
                            state = linestate.reading_quoted;
                        else if (!backslash && in == '\'')
                            state = linestate.readin_single_quote;
                        else {
                            out = in;
                            state = linestate.reading_unquoted;
                        }
                    }
                } else if (state == linestate.reading_unquoted) {
                    if (!backslash && space(in))
                        state = linestate.done;
                    else
                        out = in;
                } else if (state == linestate.reading_quoted) {
                    if (!backslash && in == '\"')
                        state = linestate.done;
                    else
                        out = in;
                } else if (state == linestate.readin_single_quote) {
                    if (in == '\'')
                        state = linestate.done;
                    else
                        out = in;
                }

                if (state == linestate.done) {
                    /* ASSERT (parm_len > 0); */
                    state = linestate.initial;
                    parameters.add(currentarg);
                    currentarg = "";
                    out = 0;
                }

                if (backslash && out != 0) {
                    if (!(out == '\\' || out == '\"' || space(out))) {
                        throw new ConfigParseError("Options warning: Bad backslash ('\\') usage");
                    }
                }
                backslash = false;
            }

            /* store parameter character */
            if (out != 0) {
                currentarg += out;
            }
        } while (pos++ < line.length());

        return parameters;
    }

    // This method is far too long
    @SuppressWarnings("ConstantConditions")
    public VpnProfile convertProfile() throws ConfigParseError, IOException {
        boolean noauthtypeset = true;
        VpnProfile np = new VpnProfile(CONVERTED_PROFILE);
        // Pull, client, tls-client
        np.clearDefaults();

        if (options.containsKey("client") || options.containsKey("pull")) {
            np.mUsePull = true;
            options.remove("pull");
            options.remove("client");
        }

        Vector<String> secret = getOption("secret", 1, 2);
        if (secret != null) {
            np.mAuthenticationType = VpnProfile.TYPE_STATICKEYS;
            noauthtypeset = false;
            np.mUseTLSAuth = true;
            np.mTLSAuthFilename = secret.get(1);
            if (secret.size() == 3)
                np.mTLSAuthDirection = secret.get(2);

        }

        Vector<Vector<String>> routes = getAllOption("route", 1, 4);
        if (routes != null) {
            String routeopt = "";
            String routeExcluded = "";
            for (Vector<String> route : routes) {
                String netmask = "255.255.255.255";
                String gateway = "vpn_gateway";

                if (route.size() >= 3)
                    netmask = route.get(2);
                if (route.size() >= 4)
                    gateway = route.get(3);

                String net = route.get(1);
                try {
                    CIDRIP cidr = new CIDRIP(net, netmask);
                    if (gateway.equals("net_gateway"))
                        routeExcluded += cidr.toString() + " ";
                    else
                        routeopt += cidr.toString() + " ";
                } catch (ArrayIndexOutOfBoundsException aioob) {
                    throw new ConfigParseError("Could not parse netmask of route " + netmask);
                } catch (NumberFormatException ne) {


                    throw new ConfigParseError("Could not parse netmask of route " + netmask);
                }

            }
            np.mCustomRoutes = routeopt;
            np.mExcludedRoutes = routeExcluded;
        }

        Vector<Vector<String>> routesV6 = getAllOption("route-ipv6", 1, 4);
        if (routesV6 != null) {
            String customIPv6Routes = "";
            for (Vector<String> route : routesV6) {
                customIPv6Routes += route.get(1) + " ";
            }

            np.mCustomRoutesv6 = customIPv6Routes;
        }

        Vector<String> routeNoPull = getOption("route-nopull", 0, 0);
        if (routeNoPull != null)
            np.mRoutenopull = true;

        // Also recognize tls-auth [inline] direction ...
        Vector<Vector<String>> tlsauthoptions = getAllOption("tls-auth", 1, 2);
        if (tlsauthoptions != null) {
            for (Vector<String> tlsauth : tlsauthoptions) {
                if (tlsauth != null) {
                    if (!tlsauth.get(1).equals("[inline]")) {
                        np.mTLSAuthFilename = tlsauth.get(1);
                        np.mUseTLSAuth = true;
                    }
                    if (tlsauth.size() == 3)
                        np.mTLSAuthDirection = tlsauth.get(2);
                }
            }
        }

        Vector<String> direction = getOption("key-direction", 1, 1);
        if (direction != null)
            np.mTLSAuthDirection = direction.get(1);

        for (String crypt: new String[]{"tls-crypt", "tls-crypt-v2"}) {
            Vector<String> tlscrypt = getOption(crypt, 1, 1);
            if (tlscrypt != null) {
                np.mUseTLSAuth = true;
                np.mTLSAuthFilename = tlscrypt.get(1);
                np.mTLSAuthDirection = crypt;
            }
        }

        Vector<Vector<String>> defgw = getAllOption("redirect-gateway", 0, 7);
        if (defgw != null) {
            checkRedirectParameters(np, defgw, true);
        }

        Vector<Vector<String>> redirectPrivate = getAllOption("redirect-private", 0, 5);
        if (redirectPrivate != null) {
            checkRedirectParameters(np, redirectPrivate, false);
        }
        Vector<String> dev = getOption("dev", 1, 1);
        Vector<String> devtype = getOption("dev-type", 1, 1);

        if ((devtype != null && devtype.get(1).equals("tun")) ||
                (dev != null && dev.get(1).startsWith("tun")) ||
                (devtype == null && dev == null)) {
            //everything okay
        } else {
            throw new ConfigParseError("Sorry. Only tun mode is supported. See the FAQ for more detail");
        }

        Vector<String> mssfix = getOption("mssfix", 0, 2);

        if (mssfix != null) {
            if (mssfix.size() >= 2) {
                try {
                    np.mMssFix = Integer.parseInt(mssfix.get(1));
                } catch (NumberFormatException e) {
                    throw new ConfigParseError("Argument to --mssfix has to be an integer");
                }
            } else {
                np.mMssFix = 1450; // OpenVPN default size
            }
            // Ignore mtu argument of OpenVPN3 and report error otherwise
            if (mssfix.size() >= 3 && !(mssfix.get(2).equals("mtu"))) {
                throw new ConfigParseError("Second argument to --mssfix unkonwn");
            }
        }


        Vector<String> tunmtu = getOption("tun-mtu", 1, 1);

        if (tunmtu != null) {
            try {
                np.mTunMtu = Integer.parseInt(tunmtu.get(1));
            } catch (NumberFormatException e) {
                throw new ConfigParseError("Argument to --tun-mtu has to be an integer");
            }
        }


        Vector<String> mode = getOption("mode", 1, 1);
        if (mode != null) {
            if (!mode.get(1).equals("p2p"))
                throw new ConfigParseError("Invalid mode for --mode specified, need p2p");
        }


        Vector<Vector<String>> dhcpoptions = getAllOption("dhcp-option", 2, 2);
        if (dhcpoptions != null) {
            for (Vector<String> dhcpoption : dhcpoptions) {
                String type = dhcpoption.get(1);
                String arg = dhcpoption.get(2);
                if (type.equals("DOMAIN")) {
                    np.mSearchDomain = dhcpoption.get(2);
                } else if (type.equals("DNS")) {
                    np.mOverrideDNS = true;
                    if (np.mDNS1.equals(VpnProfile.DEFAULT_DNS1)) {
                        np.mDNS1 = arg;
                        np.mDNS2 = "";
                    }
                    else
                        np.mDNS2 = arg;
                }
            }
        }

        Vector<String> ifconfig = getOption("ifconfig", 2, 2);
        if (ifconfig != null) {
            try {
                CIDRIP cidr = new CIDRIP(ifconfig.get(1), ifconfig.get(2));
                np.mIPv4Address = cidr.toString();
            } catch (NumberFormatException nfe) {
                throw new ConfigParseError("Could not pase ifconfig IP address: " + nfe.getLocalizedMessage());
            }

        }

        if (getOption("remote-random-hostname", 0, 0) != null)
            np.mUseRandomHostname = true;

        if (getOption("float", 0, 0) != null)
            np.mUseFloat = true;

        if (getOption("comp-lzo", 0, 1) != null)
            np.mUseLzo = true;

        Vector<String> ncp_ciphers = getOption("ncp-ciphers", 1, 1);
        Vector<String> data_ciphers = getOption("data-ciphers", 1, 1);
        Vector<String> cipher = getOption("cipher", 1, 1);

        if (cipher != null)
            np.mCipher = cipher.get(1);

        if (data_ciphers != null)
        {
            np.mDataCiphers = data_ciphers.get(1);
        }
        else if (ncp_ciphers != null)
        {
            np.mDataCiphers = ncp_ciphers.get(1);
        }
        Vector<String> tls_cert_profile = getOption("tls-cert-profile", 1, 1);
        if (tls_cert_profile != null)
        {
            String profile = tls_cert_profile.get(1);
            for (String choice : new String[]{"insecure", "preferred", "legacy", "suiteb"}) {
                if (choice.equals(profile)) {
                    np.mTlSCertProfile = profile;
                    break;
                }
            }
            if (!profile.equals(np.mTlSCertProfile))
            {
                throw new ConfigParseError("Invalid tls-cert-profile '" + profile + "'");
            }
        }

        Vector<String> provider = getOption("provider", 1, 9);
        if (provider != null)
        {
            String providers = provider.get(1).toLowerCase(Locale.ROOT);
            if (providers.equals("legacy:default") || providers.equals("default:legacy"))
                np.mUseLegacyProvider = true;

            for (String prov:provider)
            {
                if ("legacy".equals(prov.toLowerCase(Locale.ROOT)))
                {
                    np.mUseLegacyProvider = true;
                }
            }
        }


        Vector<String> compatmode = getOption("compat-mode", 1, 1);
        if (compatmode != null)
        {
            Scanner versionScanner = new Scanner(compatmode.get(1));
            versionScanner.useDelimiter("\\.");
            int major = versionScanner.nextInt();
            int minor = versionScanner.nextInt();
            int patch = versionScanner.nextInt();

            np.mCompatMode = major * 10000 + minor * 100 + patch;
        }

        Vector<String> auth = getOption("auth", 1, 1);
        if (auth != null)
            np.mAuth = auth.get(1);


        Vector<String> ca = getOption("ca", 1, 1);
        if (ca != null) {
            np.mCaFilename = ca.get(1);
        }

        Vector<Vector<String>> peerfp = getAllOption("peer-fingerprint", 1, 1);
        if (peerfp != null)
        {
            np.mCheckPeerFingerprint = true;
            for (Vector<String> fp: peerfp)
            {
                if (fp.get(1).startsWith(VpnProfile.INLINE_TAG))
                    np.mPeerFingerPrints+=fp.get(1).substring(VpnProfile.INLINE_TAG.length()) + "\n";
                else
                    np.mPeerFingerPrints+=fp.get(1) + "\n";
            }
        }


        Vector<String> cert = getOption("cert", 1, 1);
        if (cert != null) {
            np.mClientCertFilename = cert.get(1);
            np.mAuthenticationType = VpnProfile.TYPE_CERTIFICATES;
            noauthtypeset = false;
        }
        Vector<String> key = getOption("key", 1, 1);
        if (key != null)
            np.mClientKeyFilename = key.get(1);

        Vector<String> pkcs12 = getOption("pkcs12", 1, 1);
        if (pkcs12 != null) {
            np.mPKCS12Filename = pkcs12.get(1);
            np.mAuthenticationType = VpnProfile.TYPE_KEYSTORE;
            noauthtypeset = false;
        }

        Vector<String> cryptoapicert = getOption("cryptoapicert", 1, 1);
        if (cryptoapicert != null) {
            np.mAuthenticationType = VpnProfile.TYPE_KEYSTORE;
            noauthtypeset = false;
        }

        Vector<String> compatnames = getOption("compat-names", 1, 2);
        Vector<String> nonameremapping = getOption("no-name-remapping", 1, 1);
        Vector<String> tlsremote = getOption("tls-remote", 1, 1);
        if (tlsremote != null) {
            np.mRemoteCN = tlsremote.get(1);
            np.mCheckRemoteCN = true;
            np.mX509AuthType = VpnProfile.X509_VERIFY_TLSREMOTE;

            if ((compatnames != null && compatnames.size() > 2) ||
                    (nonameremapping != null))
                np.mX509AuthType = VpnProfile.X509_VERIFY_TLSREMOTE_COMPAT_NOREMAPPING;
        }

        Vector<String> verifyx509name = getOption("verify-x509-name", 1, 2);
        if (verifyx509name != null) {
            np.mRemoteCN = verifyx509name.get(1);
            np.mCheckRemoteCN = true;
            if (verifyx509name.size() > 2) {
                if (verifyx509name.get(2).equals("name"))
                    np.mX509AuthType = VpnProfile.X509_VERIFY_TLSREMOTE_RDN;
                else if (verifyx509name.get(2).equals("subject"))
                    np.mX509AuthType = VpnProfile.X509_VERIFY_TLSREMOTE_DN;
                else if (verifyx509name.get(2).equals("name-prefix"))
                    np.mX509AuthType = VpnProfile.X509_VERIFY_TLSREMOTE_RDN_PREFIX;
                else
                    throw new ConfigParseError("Unknown parameter to verify-x509-name: " + verifyx509name.get(2));
            } else {
                np.mX509AuthType = VpnProfile.X509_VERIFY_TLSREMOTE_DN;
            }

        }

        Vector<String> x509usernamefield = getOption("x509-username-field", 1, 1);
        if (x509usernamefield != null) {
            np.mx509UsernameField = x509usernamefield.get(1);
        }


        Vector<String> verb = getOption("verb", 1, 1);
        if (verb != null) {
            np.mVerb = verb.get(1);
        }


        if (getOption("nobind", 0, 0) != null)
            np.mNobind = true;

        if (getOption("persist-tun", 0, 0) != null)
            np.mPersistTun = true;

        if (getOption("push-peer-info", 0, 0) != null)
            np.mPushPeerInfo = true;

        Vector<String> connectretry = getOption("connect-retry", 1, 2);
        if (connectretry != null) {
            np.mConnectRetry = connectretry.get(1);
            if (connectretry.size() > 2)
                np.mConnectRetryMaxTime = connectretry.get(2);
        }

        Vector<String> connectretrymax = getOption("connect-retry-max", 1, 1);
        if (connectretrymax != null)
            np.mConnectRetryMax = connectretrymax.get(1);

        Vector<Vector<String>> remotetls = getAllOption("remote-cert-tls", 1, 1);
        if (remotetls != null)
            if (remotetls.get(0).get(1).equals("server"))
                np.mExpectTLSCert = true;
            else
                options.put("remotetls", remotetls);

        Vector<String> authuser = getOption("auth-user-pass", 0, 1);

        if (authuser != null) {
            if (noauthtypeset) {
                np.mAuthenticationType = VpnProfile.TYPE_USERPASS;
            } else if (np.mAuthenticationType == VpnProfile.TYPE_CERTIFICATES) {
                np.mAuthenticationType = VpnProfile.TYPE_USERPASS_CERTIFICATES;
            } else if (np.mAuthenticationType == VpnProfile.TYPE_KEYSTORE) {
                np.mAuthenticationType = VpnProfile.TYPE_USERPASS_KEYSTORE;
            }
            if (authuser.size() > 1) {
                if (!authuser.get(1).startsWith(VpnProfile.INLINE_TAG))
                    auth_user_pass_file = authuser.get(1);
                np.mUsername = null;
                useEmbbedUserAuth(np, authuser.get(1));
            }
        }

        Vector<String> authretry = getOption("auth-retry", 1, 1);
        if (authretry != null) {
            if (authretry.get(1).equals("none"))
                np.mAuthRetry = VpnProfile.AUTH_RETRY_NONE_FORGET;
            else if (authretry.get(1).equals("nointeract"))
                np.mAuthRetry = VpnProfile.AUTH_RETRY_NOINTERACT;
            else if (authretry.get(1).equals("interact"))
                np.mAuthRetry = VpnProfile.AUTH_RETRY_NOINTERACT;
            else
                throw new ConfigParseError("Unknown parameter to auth-retry: " + authretry.get(2));
        }


        Vector<String> crlfile = getOption("crl-verify", 1, 2);
        if (crlfile != null) {
            // If the 'dir' parameter is present just add it as custom option ..
            if (crlfile.size() == 3 && crlfile.get(2).equals("dir"))
                np.mCustomConfigOptions += join(" ", crlfile) + "\n";
            else
                // Save the filename for the config converter to add later
                np.mCrlFilename = crlfile.get(1);

        }


        Pair<Connection, Connection[]> conns = parseConnectionOptions(null);
        np.mConnections = conns.second;

        Vector<Vector<String>> connectionBlocks = getAllOption("connection", 1, 1);

        if (np.mConnections.length > 0 && connectionBlocks != null) {
            throw new ConfigParseError("Using a <connection> block and --remote is not allowed.");
        }

        if (connectionBlocks != null) {
            np.mConnections = new Connection[connectionBlocks.size()];

            int connIndex = 0;
            for (Vector<String> conn : connectionBlocks) {
                Pair<Connection, Connection[]> connectionBlockConnection =
                        parseConnection(conn.get(1), conns.first);

                if (connectionBlockConnection.second.length != 1)
                    throw new ConfigParseError("A <connection> block must have exactly one remote");
                np.mConnections[connIndex] = connectionBlockConnection.second[0];
                connIndex++;
            }
        }
        if (getOption("remote-random", 0, 0) != null)
            np.mRemoteRandom = true;

        Vector<String> protoforce = getOption("proto-force", 1, 1);
        if (protoforce != null) {
            boolean disableUDP;
            String protoToDisable = protoforce.get(1);
            if (protoToDisable.equals("udp"))
                disableUDP = true;
            else if (protoToDisable.equals("tcp"))
                disableUDP = false;
            else
                throw new ConfigParseError(String.format("Unknown protocol %s in proto-force", protoToDisable));

            for (Connection conn : np.mConnections)
                if (conn.mUseUdp == disableUDP)
                    conn.mEnabled = false;
        }

        // Parse OpenVPN Access Server extra
        for (String as_name_directive: new String[]{"PROFILE", "FRIENDLY_NAME"}) {
            Vector<String> friendlyname = meta.get(as_name_directive);
            if (friendlyname != null && friendlyname.size() > 1)
                np.mName = friendlyname.get(1);
        }

        // Dynamically set profile name based on connected VPN server if no friendly name is set
        if ((np.mName == null || np.mName.equals(CONVERTED_PROFILE)) && np.mConnections.length > 0) {
            Connection firstConnection = np.mConnections[0];
            if (firstConnection != null && firstConnection.mServerName != null && !firstConnection.mServerName.isEmpty()) {
                String serverName = firstConnection.mServerName;
                String countryName = extractCountryFromServerName(serverName);

                if (countryName != null && !countryName.isEmpty()) {
                    np.mName = serverName + " | " + countryName;
                } else {
                    np.mName = serverName;
                }
            }
        }

        Vector<String> ocusername = meta.get("USERNAME");
        if (ocusername != null && ocusername.size() > 1)
            np.mUsername = ocusername.get(1);

        checkIgnoreAndInvalidOptions(np);
        fixup(np);

        return np;
    }

    private String join(String s, Vector<String> str) {
        if (Build.VERSION.SDK_INT > 26)
            return String.join(s, str);
        else
            return TextUtils.join(s, str);
    }

    /**
     * Extracts the country name from a VPN server name.
     * Assumes the server name starts with a country code (e.g., "DE-MULTI-1" -> "Germany")
     * or can extract from hostname (e.g., "de-multi-1.example.com" -> "Germany")
     *
     * @param serverName The server name to extract country from
     * @return The country name, or empty string if not found
     */
    private String extractCountryFromServerName(String serverName) {
        if (serverName == null || serverName.isEmpty()) {
            return "";
        }

        // Extract the country code from the beginning (first 2 characters before the first hyphen/dot)
        String countryCode = serverName;
        int hyphenIndex = serverName.indexOf('-');
        int dotIndex = serverName.indexOf('.');

        // Find the position of the first separator (hyphen or dot)
        int separatorIndex = -1;
        if (hyphenIndex > 0 && dotIndex > 0) {
            separatorIndex = Math.min(hyphenIndex, dotIndex);
        } else if (hyphenIndex > 0) {
            separatorIndex = hyphenIndex;
        } else if (dotIndex > 0) {
            separatorIndex = dotIndex;
        }

        if (separatorIndex > 0) {
            countryCode = serverName.substring(0, separatorIndex);
        }

        // Convert country code to country name
        return countryCodeToName(countryCode.toUpperCase(Locale.ROOT));
    }


    /**
     * Converts ISO 3166-1 alpha-2 country codes to country names
     *
     * @param countryCode The 2-letter country code
     * @return The country name, or empty string if not found
     */
    private String countryCodeToName(String countryCode) {
        switch (countryCode) {
            case "AD": return "Andorra";
            case "AE": return "United Arab Emirates";
            case "AF": return "Afghanistan";
            case "AG": return "Antigua and Barbuda";
            case "AI": return "Anguilla";
            case "AL": return "Albania";
            case "AM": return "Armenia";
            case "AO": return "Angola";
            case "AQ": return "Antarctica";
            case "AR": return "Argentina";
            case "AS": return "American Samoa";
            case "AT": return "Austria";
            case "AU": return "Australia";
            case "AW": return "Aruba";
            case "AX": return "Aland Islands";
            case "AZ": return "Azerbaijan";
            case "BA": return "Bosnia and Herzegovina";
            case "BB": return "Barbados";
            case "BD": return "Bangladesh";
            case "BE": return "Belgium";
            case "BF": return "Burkina Faso";
            case "BG": return "Bulgaria";
            case "BH": return "Bahrain";
            case "BI": return "Burundi";
            case "BJ": return "Benin";
            case "BL": return "Saint Barthelemy";
            case "BM": return "Bermuda";
            case "BN": return "Brunei";
            case "BO": return "Bolivia";
            case "BQ": return "Bonaire Sint Eustatius Saba";
            case "BR": return "Brazil";
            case "BS": return "Bahamas";
            case "BT": return "Bhutan";
            case "BV": return "Bouvet Island";
            case "BW": return "Botswana";
            case "BY": return "Belarus";
            case "BZ": return "Belize";
            case "CA": return "Canada";
            case "CC": return "Cocos Islands";
            case "CD": return "Democratic Republic of Congo";
            case "CF": return "Central African Republic";
            case "CG": return "Congo";
            case "CH": return "Switzerland";
            case "CI": return "Ivory Coast";
            case "CK": return "Cook Islands";
            case "CL": return "Chile";
            case "CM": return "Cameroon";
            case "CN": return "China";
            case "CO": return "Colombia";
            case "CR": return "Costa Rica";
            case "CU": return "Cuba";
            case "CV": return "Cape Verde";
            case "CW": return "Curacao";
            case "CX": return "Christmas Island";
            case "CY": return "Cyprus";
            case "CZ": return "Czech Republic";
            case "DE": return "Germany";
            case "DJ": return "Djibouti";
            case "DK": return "Denmark";
            case "DM": return "Dominica";
            case "DO": return "Dominican Republic";
            case "DZ": return "Algeria";
            case "EC": return "Ecuador";
            case "EE": return "Estonia";
            case "EG": return "Egypt";
            case "EH": return "Western Sahara";
            case "ER": return "Eritrea";
            case "ES": return "Spain";
            case "ET": return "Ethiopia";
            case "FI": return "Finland";
            case "FJ": return "Fiji";
            case "FK": return "Falkland Islands";
            case "FM": return "Micronesia";
            case "FO": return "Faroe Islands";
            case "FR": return "France";
            case "GA": return "Gabon";
            case "GB": return "United Kingdom";
            case "GD": return "Grenada";
            case "GE": return "Georgia";
            case "GF": return "French Guiana";
            case "GG": return "Guernsey";
            case "GH": return "Ghana";
            case "GI": return "Gibraltar";
            case "GL": return "Greenland";
            case "GM": return "Gambia";
            case "GN": return "Guinea";
            case "GP": return "Guadeloupe";
            case "GQ": return "Equatorial Guinea";
            case "GR": return "Greece";
            case "GS": return "South Georgia and South Sandwich Islands";
            case "GT": return "Guatemala";
            case "GU": return "Guam";
            case "GW": return "Guinea-Bissau";
            case "GY": return "Guyana";
            case "HK": return "Hong Kong";
            case "HM": return "Heard Island and McDonald Islands";
            case "HN": return "Honduras";
            case "HR": return "Croatia";
            case "HT": return "Haiti";
            case "HU": return "Hungary";
            case "ID": return "Indonesia";
            case "IE": return "Ireland";
            case "IL": return "Israel";
            case "IM": return "Isle of Man";
            case "IN": return "India";
            case "IO": return "British Indian Ocean Territory";
            case "IQ": return "Iraq";
            case "IR": return "Iran";
            case "IS": return "Iceland";
            case "IT": return "Italy";
            case "JE": return "Jersey";
            case "JM": return "Jamaica";
            case "JO": return "Jordan";
            case "JP": return "Japan";
            case "KE": return "Kenya";
            case "KG": return "Kyrgyzstan";
            case "KH": return "Cambodia";
            case "KI": return "Kiribati";
            case "KM": return "Comoros";
            case "KN": return "Saint Kitts and Nevis";
            case "KP": return "North Korea";
            case "KR": return "South Korea";
            case "KW": return "Kuwait";
            case "KY": return "Cayman Islands";
            case "KZ": return "Kazakhstan";
            case "LA": return "Laos";
            case "LB": return "Lebanon";
            case "LC": return "Saint Lucia";
            case "LI": return "Liechtenstein";
            case "LK": return "Sri Lanka";
            case "LR": return "Liberia";
            case "LS": return "Lesotho";
            case "LT": return "Lithuania";
            case "LU": return "Luxembourg";
            case "LV": return "Latvia";
            case "LY": return "Libya";
            case "MA": return "Morocco";
            case "MC": return "Monaco";
            case "MD": return "Moldova";
            case "ME": return "Montenegro";
            case "MF": return "Saint Martin";
            case "MG": return "Madagascar";
            case "MH": return "Marshall Islands";
            case "MK": return "Macedonia";
            case "ML": return "Mali";
            case "MM": return "Myanmar";
            case "MN": return "Mongolia";
            case "MO": return "Macao";
            case "MP": return "Northern Mariana Islands";
            case "MQ": return "Martinique";
            case "MR": return "Mauritania";
            case "MS": return "Montserrat";
            case "MT": return "Malta";
            case "MU": return "Mauritius";
            case "MV": return "Maldives";
            case "MW": return "Malawi";
            case "MX": return "Mexico";
            case "MY": return "Malaysia";
            case "MZ": return "Mozambique";
            case "NA": return "Namibia";
            case "NC": return "New Caledonia";
            case "NE": return "Niger";
            case "NF": return "Norfolk Island";
            case "NG": return "Nigeria";
            case "NI": return "Nicaragua";
            case "NL": return "Netherlands";
            case "NO": return "Norway";
            case "NP": return "Nepal";
            case "NR": return "Nauru";
            case "NU": return "Niue";
            case "NZ": return "New Zealand";
            case "OM": return "Oman";
            case "PA": return "Panama";
            case "PE": return "Peru";
            case "PF": return "French Polynesia";
            case "PG": return "Papua New Guinea";
            case "PH": return "Philippines";
            case "PK": return "Pakistan";
            case "PL": return "Poland";
            case "PM": return "Saint Pierre and Miquelon";
            case "PN": return "Pitcairn";
            case "PR": return "Puerto Rico";
            case "PS": return "Palestine";
            case "PT": return "Portugal";
            case "PW": return "Palau";
            case "PY": return "Paraguay";
            case "QA": return "Qatar";
            case "RE": return "Reunion";
            case "RO": return "Romania";
            case "RS": return "Serbia";
            case "RU": return "Russia";
            case "RW": return "Rwanda";
            case "SA": return "Saudi Arabia";
            case "SB": return "Solomon Islands";
            case "SC": return "Seychelles";
            case "SD": return "Sudan";
            case "SE": return "Sweden";
            case "SG": return "Singapore";
            case "SH": return "Saint Helena";
            case "SI": return "Slovenia";
            case "SJ": return "Svalbard and Jan Mayen";
            case "SK": return "Slovakia";
            case "SL": return "Sierra Leone";
            case "SM": return "San Marino";
            case "SN": return "Senegal";
            case "SO": return "Somalia";
            case "SR": return "Suriname";
            case "SS": return "South Sudan";
            case "ST": return "Sao Tome and Principe";
            case "SV": return "El Salvador";
            case "SX": return "Sint Maarten";
            case "SY": return "Syria";
            case "SZ": return "Eswatini";
            case "TC": return "Turks and Caicos Islands";
            case "TD": return "Chad";
            case "TF": return "French Southern Territories";
            case "TG": return "Togo";
            case "TH": return "Thailand";
            case "TJ": return "Tajikistan";
            case "TK": return "Tokelau";
            case "TL": return "East Timor";
            case "TM": return "Turkmenistan";
            case "TN": return "Tunisia";
            case "TO": return "Tonga";
            case "TR": return "Turkey";
            case "TT": return "Trinidad and Tobago";
            case "TV": return "Tuvalu";
            case "TW": return "Taiwan";
            case "TZ": return "Tanzania";
            case "UA": return "Ukraine";
            case "UG": return "Uganda";
            case "UM": return "U.S. Minor Outlying Islands";
            case "US": return "United States";
            case "UY": return "Uruguay";
            case "UZ": return "Uzbekistan";
            case "VA": return "Vatican City";
            case "VC": return "Saint Vincent and the Grenadines";
            case "VE": return "Venezuela";
            case "VG": return "British Virgin Islands";
            case "VI": return "U.S. Virgin Islands";
            case "VN": return "Vietnam";
            case "VU": return "Vanuatu";
            case "WF": return "Wallis and Futuna";
            case "WS": return "Samoa";
            case "YE": return "Yemen";
            case "YT": return "Mayotte";
            case "ZA": return "South Africa";
            case "ZM": return "Zambia";
            case "ZW": return "Zimbabwe";
            default: return "";
        }
    }

    private Pair<Connection, Connection[]> parseConnection(String connection, Connection defaultValues) throws IOException, ConfigParseError {
        // Parse a connection Block as a new configuration file


        ConfigParser connectionParser = new ConfigParser();
        StringReader reader = new StringReader(connection.substring(VpnProfile.INLINE_TAG.length()));
        connectionParser.parseConfig(reader);

        Pair<Connection, Connection[]> conn = connectionParser.parseConnectionOptions(defaultValues);

        return conn;
    }

    private Pair<Connection, Connection[]> parseConnectionOptions(Connection connDefault) throws ConfigParseError {
        Connection conn;
        if (connDefault != null)
            try {
                conn = connDefault.clone();
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
                return null;
            }
        else
            conn = new Connection();

        Vector<String> port = getOption("port", 1, 1);
        if (port != null) {
            conn.mServerPort = port.get(1);
        }

        Vector<String> rport = getOption("rport", 1, 1);
        if (rport != null) {
            conn.mServerPort = rport.get(1);
        }

        Vector<String> proto = getOption("proto", 1, 1);
        if (proto != null) {
            conn.mUseUdp = isUdpProto(proto.get(1));
        }

        Vector<String> connectTimeout = getOption("connect-timeout", 1, 1);
        if (connectTimeout != null) {
            try {
                conn.mConnectTimeout = Integer.parseInt(connectTimeout.get(1));
            } catch (NumberFormatException nfe) {
                throw new ConfigParseError(String.format("Argument to connect-timeout (%s) must to be an integer: %s",
                        connectTimeout.get(1), nfe.getLocalizedMessage()));

            }
        }

        Vector<String> proxy = getOption("socks-proxy", 1, 2);
        if (proxy == null)
            proxy = getOption("http-proxy", 2, 2);

        if (proxy != null) {
            if (proxy.get(0).equals("socks-proxy")) {
                conn.mProxyType = Connection.ProxyType.SOCKS5;
                // socks defaults to 1080, http always sets port
                conn.mProxyPort = "1080";
            } else {
                conn.mProxyType = Connection.ProxyType.HTTP;
            }

            conn.mProxyName = proxy.get(1);
            if (proxy.size() >= 3)
                conn.mProxyPort = proxy.get(2);
        }

        Vector<String> httpproxyauthhttp = getOption("http-proxy-user-pass", 1, 1);
        if (httpproxyauthhttp != null)
            useEmbbedHttpAuth(conn, httpproxyauthhttp.get(1));


        // Parse remote config
        Vector<Vector<String>> remotes = getAllOption("remote", 1, 3);



        Vector <String> optionsToRemove = new Vector<>();
        // Assume that we need custom options if connectionDefault are set or in the connection specific set
        for (Map.Entry<String, Vector<Vector<String>>> option : options.entrySet()) {
            if (connDefault != null || connectionOptionsSet.contains(option.getKey())) {
                conn.mCustomConfiguration += getOptionStrings(option.getValue());
                optionsToRemove.add(option.getKey());
            }
        }
        for (String o: optionsToRemove)
            options.remove(o);

        if (!(conn.mCustomConfiguration == null || "".equals(conn.mCustomConfiguration.trim())))
            conn.mUseCustomConfig = true;

        // Make remotes empty to simplify code
        if (remotes == null)
            remotes = new Vector<Vector<String>>();

        Connection[] connections = new Connection[remotes.size()];


        int i = 0;
        for (Vector<String> remote : remotes) {
            try {
                connections[i] = conn.clone();
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
            }
            switch (remote.size()) {
                case 4:
                    connections[i].mUseUdp = isUdpProto(remote.get(3));
                case 3:
                    connections[i].mServerPort = remote.get(2);
                case 2:
                    connections[i].mServerName = remote.get(1);
            }
            i++;
        }

        return Pair.create(conn, connections);

    }

    private void checkRedirectParameters(VpnProfile np, Vector<Vector<String>> defgw, boolean defaultRoute) {

        boolean noIpv4 = false;
        if (defaultRoute)

            for (Vector<String> redirect : defgw)
                for (int i = 1; i < redirect.size(); i++) {
                    if (redirect.get(i).equals("block-local"))
                        np.mAllowLocalLAN = false;
                    else if (redirect.get(i).equals("unblock-local"))
                        np.mAllowLocalLAN = true;
                    else if (redirect.get(i).equals("!ipv4"))
                        noIpv4 = true;
                    else if (redirect.get(i).equals("ipv6"))
                        np.mUseDefaultRoutev6 = true;
                }
        if (defaultRoute && !noIpv4)
            np.mUseDefaultRoute = true;
    }

    private boolean isUdpProto(String proto) throws ConfigParseError {
        boolean isudp;
        if (proto.equals("udp") || proto.equals("udp4") || proto.equals("udp6"))
            isudp = true;
        else if (proto.equals("tcp-client") ||
                proto.equals("tcp") ||
                proto.equals("tcp4") ||
                proto.endsWith("tcp4-client") ||
                proto.equals("tcp6") ||
                proto.endsWith("tcp6-client"))
            isudp = false;
        else
            throw new ConfigParseError("Unsupported option to --proto " + proto);
        return isudp;
    }

    private void checkIgnoreAndInvalidOptions(VpnProfile np) throws ConfigParseError {
        for (String option : unsupportedOptions)
            if (options.containsKey(option))
                throw new ConfigParseError(String.format("Unsupported Option %s encountered in config file. Aborting", option));

        for (String option : ignoreOptions)
            // removing an item which is not in the map is no error
            options.remove(option);


        boolean customOptions=false;
        for (Vector<Vector<String>>  option: options.values())
        {
            for (Vector<String> optionsline : option) {
                if (!ignoreThisOption(optionsline)) {
                    customOptions = true;
                }
            }
        }
        if (customOptions) {
            np.mCustomConfigOptions = "# These options found in the config file do not map to config settings:\n"
                    + np.mCustomConfigOptions;

            for (Vector<Vector<String>> option : options.values()) {

                np.mCustomConfigOptions += getOptionStrings(option);

            }
            np.mUseCustomConfig = true;

        }
    }

    boolean ignoreThisOption(Vector<String> option) {
        for (String[] ignoreOption : ignoreOptionsWithArg) {

            if (option.size() < ignoreOption.length)
                continue;

            boolean ignore = true;
            for (int i = 0; i < ignoreOption.length; i++) {
                if (!ignoreOption[i].equals(option.get(i)))
                    ignore = false;
            }
            if (ignore)
                return true;

        }
        return false;
    }

    //! Generate options for custom options
    private String getOptionStrings(Vector<Vector<String>> option) {
        String custom = "";
        for (Vector<String> optionsline : option) {
            if (!ignoreThisOption(optionsline)) {
                // Check if option had been inlined and inline again
                if (optionsline.size() == 2 &&
                        "extra-certs".equals(optionsline.get(0))) {
                    custom += VpnProfile.insertFileData(optionsline.get(0), optionsline.get(1));
                } else {
                    for (String arg : optionsline)
                        custom += VpnProfile.openVpnEscape(arg) + " ";
                    custom += "\n";
                }
            }
        }
        return custom;
    }

    private void fixup(VpnProfile np) {
        if (np.mRemoteCN.equals(np.mServerName)) {
            np.mRemoteCN = "";
        }
    }

    private Vector<String> getOption(String option, int minarg, int maxarg) throws ConfigParseError {
        Vector<Vector<String>> alloptions = getAllOption(option, minarg, maxarg);
        if (alloptions == null)
            return null;
        else
            return alloptions.lastElement();
    }

    private Vector<Vector<String>> getAllOption(String option, int minarg, int maxarg) throws ConfigParseError {
        Vector<Vector<String>> args = options.get(option);
        if (args == null)
            return null;

        for (Vector<String> optionline : args)

            if (optionline.size() < (minarg + 1) || optionline.size() > maxarg + 1) {
                String err = String.format(Locale.getDefault(), "Option %s has %d parameters, expected between %d and %d",
                        option, optionline.size() - 1, minarg, maxarg);
                throw new ConfigParseError(err);
            }
        options.remove(option);
        return args;
    }

    enum linestate {
        initial,
        readin_single_quote, reading_quoted, reading_unquoted, done
    }

    public static class ConfigParseError extends Exception {
        private static final long serialVersionUID = -60L;

        public ConfigParseError(String msg) {
            super(msg);
        }
    }

}




