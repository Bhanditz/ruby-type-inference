package org.jetbrains.plugins.ruby.ruby.codeInsight.types;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.containers.ContainerUtilRt;
import com.intellij.util.io.StringRef;
import com.intellij.util.text.VersionComparatorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.ruby.gem.GemInfo;
import org.jetbrains.plugins.ruby.gem.util.GemSearchUtil;
import org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.methods.ArgumentInfo;
import org.jetbrains.plugins.ruby.ruby.lang.psi.controlStructures.methods.Visibility;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

class SqliteRSignatureCacheManager extends RSignatureCacheManager {
    @NotNull
    private static final Logger LOG = Logger.getInstance(SqliteRSignatureCacheManager.class.getName());
    @NotNull
    private static final String DB_PATH = "/home/user/sqlite/MyDB.db";

    @Nullable
    private static RSignatureCacheManager ourInstance;

    @NotNull
    private final Connection myConnection;

    @Nullable
    static RSignatureCacheManager getInstance() {
        if (ourInstance == null) {
            try {
                // TODO: remove the hard coded path and get it from config file
                ourInstance = new SqliteRSignatureCacheManager(DB_PATH);
            } catch (ClassNotFoundException | SQLException e) {
                LOG.info(e);
                return null;
            }
        }

        return ourInstance;
    }

    private SqliteRSignatureCacheManager(@NotNull final String dbPath) throws ClassNotFoundException, SQLException {
        Class.forName("org.sqlite.JDBC");
        myConnection = DriverManager.getConnection(String.format("jdbc:sqlite:%s", dbPath));
    }

    @Nullable
    @Override
    public String findReturnTypeNameBySignature(@Nullable final Module module, @NotNull final RSignature signature) {
        try (final Statement statement = myConnection.createStatement()) {
            final String sql = String.format("SELECT return_type_name, gem_name, gem_version FROM signatures WHERE " +
                                             "method_name = '%s' AND receiver_name = '%s' AND args_type_name = '%s';",
                                             signature.getMethodName(), signature.getReceiverName(),
                                             String.join(";", signature.getArgsTypeName()));
            final ResultSet rs = statement.executeQuery(sql);
            if (rs.next()) {
                final String gemVersion = getGemVersionByName(module, rs.getString("gem_name"));
                final List<Couple<String>> versionsAndReturnTypes = new ArrayList<>();
                do {
                    versionsAndReturnTypes.add(Couple.of(rs.getString("gem_version"), rs.getString("return_type_name")));
                } while (rs.next());

                return findReturnTypeNameByGemVersion(gemVersion, versionsAndReturnTypes);
            }
        } catch (SQLException e) {
            LOG.info(e);
        }

        return null;
    }

    @Override
    public void recordSignature(@NotNull final RSignature signature, @NotNull final String returnTypeName,
                                @NotNull final String gemName, @NotNull final String gemVersion) {
        try (final Statement statement = myConnection.createStatement()) {
            final String argsInfoSerialized = signature.getArgsInfo().stream()
                    .map(argInfo -> argInfo.getName() + "," + getRubyArgTypeRepresentation(argInfo.getType()))
                    .collect(Collectors.joining(";"));
            final String sql = String.format("INSERT OR REPLACE INTO signatures " +
                                             "values('%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s');",
                                             signature.getMethodName(), signature.getReceiverName(),
                                             String.join(";", signature.getArgsTypeName()), argsInfoSerialized,
                                             returnTypeName, gemName, gemVersion, signature.getVisibility());
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            LOG.info(e);
        }
    }

    @Override
    public void clearCache() {
        try (final Statement statement = myConnection.createStatement()) {
            final String sql = "DELETE FROM signatures;";
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            LOG.info(e);
        }
    }

    @NotNull
    @Override
    public List<ArgumentInfo> getMethodArgsInfo(@NotNull final String methodName, @Nullable String receiverName) {
         try (final Statement statement = myConnection.createStatement()) {
            final String sql = String.format("SELECT args_info FROM signatures " +
                                             "WHERE method_name = '%s' AND receiver_name = '%s';",
                                             methodName, receiverName);
            final ResultSet signatures = statement.executeQuery(sql);
            if (signatures.next()) {
                return parseArgsInfo(signatures.getString("args_info"));
            }
        } catch (SQLException | IllegalArgumentException e) {
            LOG.info(e);
        }

        return ContainerUtilRt.emptyList();
    }

    @NotNull
    @Override
    protected Set<RSignature> getReceiverMethodSignatures(@NotNull final String receiverName) {
        final Set<RSignature> receiverMethodSignatures = new HashSet<>();

        try (final Statement statement = myConnection.createStatement()) {
            final String sql = String.format("SELECT method_name, visibility, args_type_name, args_info FROM signatures " +
                                             "WHERE receiver_name = '%s';", receiverName);
            final ResultSet signatures = statement.executeQuery(sql);
            while (signatures.next()) {
                final String methodName = signatures.getString("method_name");
                final Visibility visibility = Visibility.valueOf(signatures.getString("visibility"));
                final List<ArgumentInfo> argsInfo = parseArgsInfo(signatures.getString("args_info"));
                final List<String> argsTypeName = StringUtil.splitHonorQuotes(signatures.getString("args_type_name"), ';');
                final RSignature signature = new RSignature(methodName, receiverName, visibility, argsInfo, argsTypeName);
                receiverMethodSignatures.add(signature);
            }
        } catch (SQLException | IllegalArgumentException e) {
            LOG.info(e);
        }

        return receiverMethodSignatures;
    }

    @NotNull
    private static String getGemVersionByName(@Nullable final Module module, @NotNull final String gemName) {
        if (module != null && !gemName.isEmpty()) {
            final GemInfo gemInfo = GemSearchUtil.findGemEx(module, gemName);
            if (gemInfo != null) {
                return StringUtil.notNullize(gemInfo.getRealVersion());
            }
        }

        return "";
    }

    @NotNull
    private static List<ArgumentInfo> parseArgsInfo(@NotNull final String argsInfoSerialized) {
        try {
            return StringUtil.splitHonorQuotes(argsInfoSerialized, ';').stream()
                    .map(argInfo -> StringUtil.splitHonorQuotes(argInfo, ','))
                    .map(argInfo -> new ArgumentInfo(StringRef.fromString(argInfo.get(1)),
                                                     getArgTypeByRubyRepresentation(argInfo.get(0))))
                    .collect(Collectors.toList());
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @NotNull
    private String findReturnTypeNameByGemVersion(@NotNull final String gemVersion, @NotNull final List<Couple<String>> versionsAndReturnTypes) {
        final Comparator<Couple<String>> versionsAndReturnTypesComparator = (Couple<String> vt1, Couple<String> vt2) ->
                VersionComparatorUtil.compare(vt1.getFirst(), vt2.getFirst());
        final NavigableSet<Couple<String>> sortedSet = new TreeSet<>(versionsAndReturnTypesComparator);
        sortedSet.addAll(versionsAndReturnTypes);

        final Couple<String> upperBound = sortedSet.ceiling(Couple.of(gemVersion, null));
        final Couple<String> lowerBound = sortedSet.floor(Couple.of(gemVersion, null));
        if (upperBound == null) {
            return lowerBound.getSecond();
        } else if (lowerBound == null) {
            return upperBound.getSecond();
        } else if (upperBound == lowerBound) {
            return upperBound.getSecond();
        } else if (firstStringCloser(gemVersion, upperBound.getFirst(), lowerBound.getFirst())) {
            return upperBound.getSecond();
        } else {
            return lowerBound.getSecond();
        }
    }

    private boolean firstStringCloser(@NotNull final String gemVersion,
                                      @NotNull final String firstVersion, @NotNull final String secondVersion) {
        final int lcpLengthFirst = longestCommonPrefixLength(gemVersion, firstVersion);
        final int lcpLengthSecond = longestCommonPrefixLength(gemVersion, secondVersion);
        return (lcpLengthFirst > lcpLengthSecond || lcpLengthFirst > 0 && lcpLengthFirst == lcpLengthSecond &&
                Math.abs(gemVersion.charAt(lcpLengthFirst) - firstVersion.charAt(lcpLengthFirst)) <
                Math.abs(gemVersion.charAt(lcpLengthFirst) - secondVersion.charAt(lcpLengthSecond)));
    }

    private static int longestCommonPrefixLength(@NotNull final String str1, @NotNull final String str2) {
        final int minLength = Math.min(str1.length(), str2.length());
        for (int i = 0; i < minLength; i++) {
            if (str1.charAt(i) != str2.charAt(i)) {
                return i;
            }
        }

        return minLength;
    }

    @NotNull
    private static String getRubyArgTypeRepresentation(@NotNull final ArgumentInfo.Type type) {
        switch (type) {
            case SIMPLE:
                return "req";
            case ARRAY:
                return "rest";
            case HASH:
                return "keyrest";
            case BLOCK:
                return "block";
            case PREDEFINED:
                return "opt";
            default:
                throw new IllegalArgumentException();
        }
    }

    @NotNull
    private static ArgumentInfo.Type getArgTypeByRubyRepresentation(@NotNull final String argTypeRepresentation) {
        switch (argTypeRepresentation) {
            case "req":
                return ArgumentInfo.Type.SIMPLE;
            case "rest":
                return ArgumentInfo.Type.ARRAY;
            case "keyrest":
                return ArgumentInfo.Type.HASH;
            case "block":
                return ArgumentInfo.Type.BLOCK;
            case "opt":
            case "key":
            case "keyreq":
                return ArgumentInfo.Type.PREDEFINED;
            default:
                throw new IllegalArgumentException();
        }
    }
}