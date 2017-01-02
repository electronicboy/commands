/*
 * Copyright (c) 2016. Starlis LLC / dba Empire Minecraft
 *
 * This source code is proprietary software and must not be redistributed without Starlis LLC's approval
 *
 */

package co.aikar.commands;

import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Subcommand;
import co.aikar.commands.annotation.Usage;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.StringUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class BaseCommand extends Command {

    private final SetMultimap<String, RegisteredCommand> subCommands = HashMultimap.create();

    protected String execLabel;
    protected String execSubcommand;
    protected String[] origArgs;
    protected final Plugin plugin;


    public BaseCommand(Plugin plugin) {
        this(null, plugin);
    }

    public BaseCommand(String cmd, Plugin plugin) {
        super(cmd);
        this.plugin = plugin;
        final Class<? extends BaseCommand> self = this.getClass();
        CommandAlias rootCmdAlias = self.getAnnotation(CommandAlias.class);
        if (cmd == null) {
            if (rootCmdAlias == null) {
                cmd = "__" + self.getSimpleName();
            } else {
                cmd = Patterns.PIPE.split(rootCmdAlias.value())[0];
            }
            cmd = cmd.toLowerCase();
            setName(cmd);
            setLabel(cmd);
        }

        final Description cmdDesc = self.getAnnotation(Description.class);
        if (cmdDesc != null ) {
            this.description = cmdDesc.value();
        } else {
            this.description = cmd + " commands";
        }

        final Usage cmdUsage = self.getAnnotation(Usage.class);

        if (cmdUsage != null) {
            this.usageMessage = cmdUsage.value();
        } else {
            this.usageMessage = "/" + cmd;
        }

        final CommandPermission perm = self.getAnnotation(CommandPermission.class);
        if (perm != null) {
            this.setPermission(perm.value());
        }

        boolean foundDefault = false;
        for (Method method : self.getDeclaredMethods()) {
            method.setAccessible(true);
            String sublist = null;
            final Subcommand sub = method.getAnnotation(Subcommand.class);
            final Default def = method.getAnnotation(Default.class);

            final CommandAlias commandAliases = method.getAnnotation(CommandAlias.class);

            if (def != null) {
                if (!foundDefault) {
                    registerSubcommand(method, "__default");
                    foundDefault = true;
                } else {
                    SneakyThrow.sneaky(new InvalidConfigurationException("Multiple @Default commands"));
                }
            }
            if (sub != null) {
                sublist = sub.value();
            } else if (commandAliases != null) {
                sublist = commandAliases.value();
            }
            if (sublist != null) {
                registerSubcommand(method, sublist);
            }
        }

        try {
            Method unknown = self.getMethod("onUnknown", CommandSender.class, String.class, String[].class);
            unknown.setAccessible(true);
            registerSubcommand(unknown, "__unknown");
        } catch (NoSuchMethodException ignored) {}


        if (rootCmdAlias != null) {
            List<String> cmdList = new ArrayList<>();
            Collections.addAll(cmdList, Patterns.PIPE.split(rootCmdAlias.value().toLowerCase()));
            cmdList.remove(cmd);
            for (String cmdAlias : cmdList) {
               register(cmdAlias, new ForwardingCommand(this));
            }
        }

        register(getName(), this);
    }

    private boolean register(String name, Command cmd) {
        return Bukkit.getServer().getCommandMap().register(name.toLowerCase(), plugin.getName().toLowerCase().trim(), cmd);
    }

    private void registerSubcommand(Method method, String subCommand) {
        subCommand = subCommand.toLowerCase();
        final String[] subCommandParts = Patterns.SPACE.split(subCommand);
        // Must run getSubcommandPossibility BEFORE we rewrite it just after this.
        List<String> cmdList = getSubCommandPossibilityList(subCommandParts);

        // Strip pipes off for auto complete addition
        for (int i = 0; i < subCommandParts.length; i++) {
            subCommandParts[i] = Patterns.PIPE.split(subCommandParts[i])[0];
        }
        String prefSubCommand = StringUtils.join(subCommandParts, " ");
        final CommandAlias cmdAlias = method.getAnnotation(CommandAlias.class);

        final String[] aliasNames = cmdAlias != null ? Patterns.PIPE.split(cmdAlias.value().toLowerCase()) : null;
        String cmdName = aliasNames != null ? aliasNames[0] : getLabel() + " ";
        RegisteredCommand cmd = new RegisteredCommand(this, cmdName, method, prefSubCommand);

        for (String subcmd : cmdList) {
            subCommands.put(subcmd, cmd);
        }

        if (aliasNames != null) {
            for (String name : aliasNames) {
                register(name, new ForwardingCommand(this, subCommandParts));
            }
        }


    }

    /**
     * Takes a string like "foo|bar baz|qux" and generates a list of
     * - foo baz
     * - foo qux
     * - bar baz
     * - bar qux
     *
     * For every possible sub command combination
     *
     * @param subCommandParts
     * @return List of all sub command possibilities
     */
    private static List<String> getSubCommandPossibilityList(String[] subCommandParts) {
        int i = 0;
        ArrayList<String> current = null;
        while (true) {
            ArrayList<String> newList = new ArrayList<>();

            if (i < subCommandParts.length) {
                for (String s1 : Patterns.PIPE.split(subCommandParts[i])) {
                    if (current != null) {
                        newList.addAll(current.stream().map(s -> s + " " + s1).collect(Collectors.toList()));
                    } else {
                        newList.add(s1);
                    }
                }
            }

            if (i + 1 < subCommandParts.length) {
                current = newList;
                i = i + 1;
                continue;
            }

            return newList;
        }
    }

    @Override
    public final boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) {
            return true;
        }
        commandLabel = commandLabel.toLowerCase();
        if (preCommand(sender, commandLabel, args)) {
            return true;
        }

        execSubcommand = null;
        execLabel = commandLabel;
        origArgs = args;

        if (args.length == 0) {
            onDefault(sender, commandLabel);
            return true;
        }

        CommandSearch cmd = findSubCommand(args);
        if (cmd != null) {
            execSubcommand = cmd.getCheckSub();
            final String[] execargs = Arrays.copyOfRange(args, cmd.argIndex, args.length);
            executeCommand(sender, execargs, cmd.cmd);
            return true;
        }

        if (!onUnknown(sender, commandLabel, args)) {
            help(sender, args);
        }
        return true;
    }

    private CommandSearch findSubCommand(String[] args) {
        return findSubCommand(args, false);
    }
    private CommandSearch findSubCommand(String[] args, boolean completion) {
        for (int i = args.length; i >= 0; i--) {
            String checkSub = StringUtils.join(args, " ", 0, i).toLowerCase();
            Set<RegisteredCommand> cmds = subCommands.get(checkSub);

            final int extraArgs = args.length - i;
            if (!cmds.isEmpty()) {
                RegisteredCommand cmd = null;
                if (cmds.size() == 1) {
                    cmd = Iterables.getOnlyElement(cmds);
                } else {
                    Optional<RegisteredCommand> optCmd = cmds.stream().filter(c -> {
                        int nonSender = c.nonSenderAwareResolvers;
                        int partialSender = c.optionalResolvers;
                        return extraArgs <= nonSender + partialSender && (completion || extraArgs >= nonSender);
                    }).sorted((c1, c2) -> {
                        int a = c1.nonSenderAwareResolvers + c1.optionalResolvers;
                        int b = c2.nonSenderAwareResolvers + c2.optionalResolvers;

                        if (a == b) {
                            return 0;
                        }
                        return a < b ? 1 : -1;
                    }).findFirst();
                    if (optCmd.isPresent()) {
                        cmd = optCmd.get();
                    }
                }
                if (cmd != null) {
                    return new CommandSearch(cmd, i, checkSub);
                }
            }
        }
        return null;
    }

    private static void executeCommand(CommandSender sender, String[] args, RegisteredCommand cmd) {
        if (cmd.hasPermission(sender)) {
            List<String> sargs = Lists.newArrayList(args);
            cmd.invoke(sender, sargs);
        } else {
            CommandUtil.sendMsg(sender, "&cI'm sorry, but you do not have permission to perform this command.");
        }
    }

    public boolean canExecute(CommandSender sender, RegisteredCommand cmd) {
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String commandLabel, String[] args)
        throws IllegalArgumentException {

        commandLabel = commandLabel.toLowerCase();


        final CommandSearch search = findSubCommand(args, true);

        if (search != null) {
            args = Arrays.copyOfRange(args, search.argIndex, args.length);
            return completeCommand(sender, search.cmd, args, commandLabel);
        }

        String argString = StringUtils.join(args, " ").toLowerCase();

        final List<String> cmds = new ArrayList<>();

        for (Map.Entry<String, RegisteredCommand> entry : subCommands.entries()) {
            final String key = entry.getKey();
            if (key.startsWith(argString) && !"__unknown".equals(key)) {
                final RegisteredCommand value = entry.getValue();
                if (!value.hasPermission(sender)) {
                    continue;
                }
                String prefCommand = value.prefSubCommand;

                final String[] psplit = Patterns.SPACE.split(prefCommand);
                cmds.add(psplit[args.length - 1]);
            }
        }

        final Set<RegisteredCommand> unknownCmds = subCommands.get("__unknown");
        if (cmds.isEmpty() && !unknownCmds.isEmpty()) {
            RegisteredCommand unknownCommand = null;
            if (unknownCmds.size() == 1) {
                unknownCommand = Iterables.getOnlyElement(unknownCmds);
            }
            if (unknownCommand != null) {
                return completeCommand(sender, unknownCommand, args, commandLabel);
            }
        }

        return filterTabComplete(args[args.length-1], cmds);
    }

    private List<String> completeCommand(CommandSender sender, RegisteredCommand cmd, String[] args, String commandLabel) {
        if (args.length > cmd.nonSenderAwareResolvers + cmd.optionalResolvers) {
            return ImmutableList.of();
        }
        if (args.length == 0 || cmd.complete == null) {
            return args.length < 2 ? super.tabComplete(sender, commandLabel, args) : ImmutableList.of();
        }

        String[] completions = Patterns.SPACE.split(cmd.complete.value());
        final int argIndex = args.length - 1;

        String input = args[argIndex];
        final String completion = argIndex < completions.length ? completions[argIndex] : null;

        if ("@players".equals(completion)) {
            return super.tabComplete(sender, commandLabel, args);
        }
        List<String> cmds = CommandCompletions.of(sender, completion, input);
        if (cmds.isEmpty()) {
            cmds = ImmutableList.of(input);
        }
        return filterTabComplete(args[(argIndex)], cmds);
    }

    private static List<String> filterTabComplete(String arg, List<String> cmds) {
        return cmds.stream()
                   .distinct()
                   .filter(cmd -> cmd != null && (arg.isEmpty() || StringUtil.startsWithIgnoreCase(cmd, arg)))
                   .collect(Collectors.toList());
    }

    public void help(CommandSender sender, String[] args) {
        CommandUtil.sendMsg(sender, "&cUnknown Command, please type &f/help");
    }

    public void onDefault(CommandSender sender, String commandLabel) {
        executeDefault(sender);
    }
    public boolean onUnknown(CommandSender sender, String commandLabel, String[] args) {
        help(sender, args);
        return true;
    }
    public boolean executeDefault(CommandSender sender,  String... args) {
        final Set<RegisteredCommand> defs = subCommands.get("__default");
        RegisteredCommand def = null;
        if (!defs.isEmpty()) {
            if (defs.size() == 1) {
                def = Iterables.getOnlyElement(defs);
            }
            if (def != null) {
                executeCommand(sender, args, def);
                return true;
            }
        }
        return false;
    }

    public boolean preCommand(CommandSender sender, String commandLabel, String[] args) {
        return false;
    }

    public void doHelp(CommandSender sender, String... args) {
        help(sender, args);
    }

    public void showSyntax(CommandSender sender,  RegisteredCommand cmd) {
        CommandUtil.sendMsg(sender, "&cUsage: /" + cmd.command + " " + cmd.syntax);
    }

    /*@Data*/ /*@AllArgsConstructor*/
    private static class CommandSearch { RegisteredCommand cmd; int argIndex; String checkSub;

        public CommandSearch(RegisteredCommand cmd, int argIndex, String checkSub) {
            this.cmd = cmd;
            this.argIndex = argIndex;
            this.checkSub = checkSub;
        }

        public RegisteredCommand getCmd() {
            return this.cmd;
        }

        public int getArgIndex() {
            return this.argIndex;
        }

        public String getCheckSub() {
            return this.checkSub;
        }

        public void setCmd(RegisteredCommand cmd) {
            this.cmd = cmd;
        }

        public void setArgIndex(int argIndex) {
            this.argIndex = argIndex;
        }

        public void setCheckSub(String checkSub) {
            this.checkSub = checkSub;
        }

        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof CommandSearch)) {
                return false;
            }
            final CommandSearch other = (CommandSearch) o;
            if (!other.canEqual(this)) {
                return false;
            }
            final Object this$cmd = this.getCmd();
            final Object other$cmd = other.getCmd();
            if (this$cmd == null ? other$cmd != null : !this$cmd.equals(other$cmd)) {
                return false;
            }
            if (this.getArgIndex() != other.getArgIndex()) {
                return false;
            }
            final Object this$checkSub = this.getCheckSub();
            final Object other$checkSub = other.getCheckSub();
            if (this$checkSub == null ? other$checkSub != null : !this$checkSub.equals(other$checkSub)) {
                return false;
            }
            return true;
        }

        public int hashCode() {
            final int PRIME = 59;
            int result = 1;
            final Object $cmd = this.getCmd();
            result = result * PRIME + ($cmd == null ? 43 : $cmd.hashCode());
            result = result * PRIME + this.getArgIndex();
            final Object $checkSub = this.getCheckSub();
            result = result * PRIME + ($checkSub == null ? 43 : $checkSub.hashCode());
            return result;
        }

        protected boolean canEqual(Object other) {
            return other instanceof CommandSearch;
        }

        public String toString() {
            return "com.empireminecraft.commands.EmpireCommand.CommandSearch(cmd=" + this.getCmd() + ", argIndex=" + this.getArgIndex() + ", checkSub=" +
                this.getCheckSub() + ")";
        }
    }
}
