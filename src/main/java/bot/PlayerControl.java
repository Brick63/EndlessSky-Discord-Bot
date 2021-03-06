package bot;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import de.btobastian.sdcf4j.Command;
import de.btobastian.sdcf4j.CommandExecutor;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.managers.AudioManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class PlayerControl
implements CommandExecutor{

	private final AudioPlayerManager playerManager;
	private final Map<Long, GuildMusicManager> musicManagers;
	private Map<String, AudioPlayerVoteHandler> voteHandlers;
	private ESBot bot;



	public PlayerControl(ESBot bot, JDA jda){
		this.bot = bot;
		this.musicManagers = new HashMap<>();
		new AudioTimeoutControl(musicManagers, jda);
		this.playerManager = new DefaultAudioPlayerManager();
		this.voteHandlers = new HashMap<String, AudioPlayerVoteHandler>();
		AudioSourceManagers.registerRemoteSources(playerManager);
		AudioSourceManagers.registerLocalSource(playerManager);
	}



	private synchronized GuildMusicManager getGuildAudioPlayer(Guild guild){
		long guildId = Long.parseLong(guild.getId());
		GuildMusicManager musicManager = musicManagers.get(guildId);

		if(musicManager == null){
			musicManager = new GuildMusicManager(playerManager, this, guild);
			musicManagers.put(guildId, musicManager);
		}
		guild.getAudioManager().setSendingHandler(musicManager.getSendHandler());
		return musicManager;
	}



	@Command(aliases = {"-play"}, description = "Use to request a song while in a voicechannel. If no url is given, it will perform a search on youtube with the given words. Can process multiple inputs separated by commata. As URLs, songs and Playlists from Soundcloud and YouTube can be used", usage = "-play URL [, URL 2, ...]", privateMessages = false)
	public void onPlayCommand(Guild guild, TextChannel channel, String[] args, User author, Message msg) {
		if(author.isBot()) return;
		Member requester = guild.getMember(author);
		if(!channel.getTopic().contains("spam") && !channel.getName().contains("spam")){}
		else if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true)))
			channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
		else if(args.length > 0 && requester.getVoiceState().getChannel() != null){
			checkVoiceChannel(guild.getAudioManager(), requester);
			for(String query : normalize(args))
				loadAndPlay(guild, channel, query, requester, true);
			msg.delete().queue();
		}
	}


	/**
	 * Set up an array of strings with a length equal to the amount of songs requested
	 * (separated by commas). Each string in the array is one request.
	 * @param  String[] args          A comma-separated list of requests to make.
	 * @return          String array with the correct prefixes for the input requests.
	 */
	private String[] normalize(String[] args){
		int counter = 0;
		// Determine the total number of requests.
		for(String s : args)
			if(s.endsWith(","))
				++counter;
		String[] output = new String[counter + 1];

		int i = 0;
		for(String s : args){
			boolean endOfQuery = false;
			if(s.endsWith(",")){
				endOfQuery = true;
				s = s.substring(0, s.length() - 1);
			}
			if(output[i] == null){
				if(s.contains("soundcloud.com") || s.contains("youtu.be") || s.startsWith("/") || s.substring(1).startsWith(":\\"))
					output[i] = s;
				else if(s.contains("://youtube.") || s.contains("www.youtube."))
					output[i] = s.replaceAll("youtube.*/", "youtube.com/");
				else
					output[i] = "ytsearch: " + s;
			}
			else
				output[i] += " " + s;

			if(endOfQuery)
				++i;
		}

		return output;
	}



	@Command(aliases = {"-skip", "-next"}, description = "Skip the current song and start the next one in the queue.\n\nRequires the \"DJ\" role, or a vote will be started. Can be used to skip multiple songs by appending a number to the command", usage = "-skip [amount]\n-next [amount]", privateMessages = false)
	public void onSkipCommand(Guild guild, TextChannel channel, User author, Message msg){
		if(author.isBot() || (!channel.getTopic().contains("spam") && !channel.getName().contains("spam")))
			return;
		Member requester = guild.getMember(author);
		String countStr = msg.getContentRaw().indexOf(" ") < 0 ? ""
				: msg.getContentRaw().substring(msg.getContentRaw().indexOf(" ")).trim();
		int count = countStr.length() == 0 ? 1 : Math.max(new Integer(countStr).intValue(), 1);
		AudioPlayerVoteHandler voteHandler = getVoteHandler(guild, "skip");
		if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true)))
			channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
		else if(canDoCommand(guild, requester)){
			// DJs do what they want.
			if(hasDJPerms(requester, channel, guild)){
				skipTrack(channel, requester, count);
				voteHandler.clear();
			}
			else{
				List<AudioTrack> queue = getGuildAudioPlayer(guild).scheduler.getQueue();
				// The requester of a track can always skip it.
				boolean canSkip = getGuildAudioPlayer(guild).player.getPlayingTrack().getUserData().equals(requester);
				// Determine how many of the to-skip tracks belong to this requester.
				if(canSkip){
					int autoSkip = 0;
					while(autoSkip < count - 1 && queue.get(autoSkip).getUserData().equals(requester))
						++autoSkip;
					// Advance through all auto-skippable tracks.
					skipTrack(channel, requester, 1 + autoSkip);
					voteHandler.clear();
					count -= autoSkip + 1;
				}
				// Call for a vote to skip any remaining tracks.
				if(count > 0 && vote(voteHandler, requester, channel, "skip"))
					skipTrack(channel, voteHandler.getRequester(), count);
			}
			msg.delete().queue();
		}
	}



	@Command(aliases = {"-current"}, description = "Displays the current audiotrack.", usage = "-current", privateMessages = false)
	public void onCurrentCommand(Guild guild, TextChannel channel, Message msg, User author){
		if(author.isBot() || (!channel.getTopic().contains("spam") && !channel.getName().contains("spam")))
			return;
		Member requester = guild.getMember(author);
		if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true)))
			channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
		else if(canDoCommand(guild, requester)){
			AudioTrack track = getGuildAudioPlayer(guild).player.getPlayingTrack();
			EmbedBuilder eb = new EmbedBuilder();
			eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
			eb.setColor(guild.getMember(bot.getSelf()).getColor());
			eb.setThumbnail(Helper.getTrackThumbnail(track));
			if(track != null)
				eb.setDescription(NowPlayingInfo(track));
			else
				eb.setDescription("The player is not currently playing anything!");

			// Post the message, and update the progress every 15 seconds.
			channel.sendMessage(eb.build()).queue((message) -> {
				while (track.equals(getGuildAudioPlayer(guild).player.getPlayingTrack())){
					eb.setDescription(NowPlayingInfo(track));
					message.editMessage(eb.build()).queue();
					// This does not affect any other commands
					try { TimeUnit.SECONDS.sleep(15); } catch (InterruptedException e) {}
				}
			});
			msg.delete().queue();
		}
	}



	@Command(aliases = {"-queue"}, description = "Displays the current queue, or the given page of the current queue. Can also print the queue & currently playing track to a .txt file", usage = "-queue [X]\n-queue print", privateMessages = false)
	public void onQueueCommand(Guild guild, TextChannel channel, Message msg, String[] args, User author){
		if(author.isBot() || (!channel.getTopic().contains("spam") && !channel.getName().contains("spam")))
			return;
		String[] parsed = Helper.getWords(args);
		if(args.length > 0 && args[0].equalsIgnoreCase("print")){
				String name = "";
				for (int i = 1; i < args.length; ++i)
					name += " " + args[i];
				printQueue(channel, name);
		}
		else{
			String countStr = msg.getContentRaw().indexOf(" ") < 0 ? ""
					: msg.getContentRaw().substring(msg.getContentRaw().indexOf(" ")).trim();
			// Check if the command was followed by a space indicating an argument to indicate the page of the queue to display.
			// Then set 'CountStr' to everything following the space in the command.
			int showFrom = countStr.length() == 0 ? 1 : Math.max(new Integer(countStr).intValue() * 10 - 9, 1);
			// Check if there actually was anything after the space. If not, set the position in the queue for the first track to be listed as '1'.
			// If there is an argument, check if it is bigger than 1 and set the 'ShowFrom' value to the first position of the first track that would be displayed from that page.
			Member requester = guild.getMember(author);
			if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true)))
				channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
			else if(canDoCommand(guild, requester)){
				LinkedList<AudioTrack> queue = getGuildAudioPlayer(guild).scheduler.getQueue();
				int qsize = queue.size();
				StringBuilder sb = new StringBuilder("Current Queue:\n");
				EmbedBuilder eb = new EmbedBuilder();
				eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
				eb.setColor(guild.getMember(bot.getSelf()).getColor());
				eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/info.png");
				if(queue.isEmpty())
					sb.append("The queue is empty!");
				else{
					if(showFrom >= qsize)
						showFrom = qsize - (qsize % 10) == qsize ? showFrom = qsize - 9 : qsize - (qsize % 10) + 1;
					// If the page number requested is higher than the total number of pages, start on the last page.
					int trackCount = 0 + showFrom;
					// Create a variable to count the position of the track being added to the output list.
					int countMax = trackCount + 9 <= qsize ? trackCount + 9 : qsize;
					// Create a variable to show the last track to be put into the output list.
					long queueLength = 0;
					sb.append("Entries: " + qsize + "\n");
					int queuePos = 1;
					for(AudioTrack track : queue){
						queueLength += track.getDuration();
						if(trackCount <= countMax && queuePos >= trackCount){
							sb.append("`" + (trackCount) + ".` `[" + getTimestamp(track.getDuration()) + "]` ");
							sb.append(track.getInfo().title + "\n");
							++trackCount;
						}
						++queuePos;
					}
					sb.append("\n").append("Showing Page " + ((showFrom - 1) / 10 + 1) + "/" + (qsize / 10 + ((qsize % 10) & 1))
							+ ", Tracks " + (showFrom) + " - " + countMax + "/" + qsize + ".");
					sb.append("\n").append("Total Queue Time Length: ").append(getTimestamp(queueLength));
				}
				eb.setDescription(sb.toString());
				channel.sendMessage(eb.build()).queue();
				msg.delete().queue();
			}
		}
	}



	@Command(aliases = {"-shuffle"}, description = "Shuffle the queue.\n\nRequires the \"DJ\" role, or starts a vote.", usage = "-shuffle", privateMessages = false)
	public void onShuffleCommand(Guild guild, TextChannel channel, User author, Message msg){
		if(author.isBot() || (!channel.getTopic().contains("spam") && !channel.getName().contains("spam")))
			return;
		Member requester = guild.getMember(author);
		AudioPlayerVoteHandler voteHandler = getVoteHandler(guild, "shuffle");
		if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true)))
			channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
		else if(canDoCommand(guild, requester)){
			if(hasDJPerms(requester, channel, guild)){
				shuffle(guild, requester, msg, channel);
				voteHandler.clear();
			}
			else if(vote(voteHandler, requester, channel, "shuffle"))
				shuffle(guild, voteHandler.getRequester(), msg, channel);
			msg.delete().queue();
		}
	}




	@Command(aliases = {"-stop"}, description = "Stop the music, clear the queue, and disconnect the bot from the channel.\n\nRequires the \"DJ\" role, or starts a vote.", usage = "-stop", privateMessages = false)
	public void onStopCommand(Guild guild, TextChannel channel, User author, Message msg){
		if(author.isBot() || (!channel.getTopic().contains("spam") && !channel.getName().contains("spam")))
			return;
		Member requester = guild.getMember(author);
		AudioPlayerVoteHandler voteHandler = getVoteHandler(guild, "stop");
		if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true)))
			channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
		else if(canDoCommand(guild, requester)){
			if(hasDJPerms(requester, channel, guild)){
				stopPlayback(guild, requester, msg, channel);
				voteHandler.clear();
			}
			else if(vote(voteHandler, requester, channel, "stop"))
				stopPlayback(guild, requester, msg, channel);
		}
		else{
			EmbedBuilder eb = new EmbedBuilder();
			eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
			eb.setDescription("The player is not currently playing anything!");
			eb.setColor(guild.getMember(bot.getSelf()).getColor());
			eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/info.png");
			channel.sendMessage(eb.build()).queue();
			msg.delete().queue();
		}
	}



	@Command(aliases = {"-playlist"}, description = "This Command saves YouTube or Soundcloud playlists to be quickly accessible. A playlist is associated with a case-insensitive Key.", usage = "-playlist <X>\n-playlist save <X> <URL>\n-playlist info <X>\n-playlist list\n-playlist edit <X> <URL>\n-playlist delete <X>", privateMessages = false)
	public void onPlaylistCommand(Guild guild, TextChannel channel, User author, String[] args, Message msg){
		if(author.isBot() || (!channel.getTopic().contains("spam") && !channel.getName().contains("spam")))
			return;
		String[] parsed = Helper.getWords(args);
		Member requester = guild.getMember(author);
		EmbedBuilder eb = new EmbedBuilder();
		eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
		eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/info.png");
		eb.setColor(guild.getMember(bot.getSelf()).getColor());

		if(args[0].equalsIgnoreCase("save")){
			if(Helper.getPlaylistbyKey(args[1]) != null)
				eb.setDescription("This Playlist already exists.");
			else if(args[1].equalsIgnoreCase("save") || args[1].equalsIgnoreCase("edit")
					|| args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("list")
					|| args[1].equalsIgnoreCase("delete"))
				eb.setDescription("Find a better name!");
			else if(args[2].contains("youtube.com/") || args[2].contains("soundcloud.com/")){
				Helper.savePlaylist(args[1], args[2], author.getName() + "#" + author.getDiscriminator(), author.getIdLong());
				eb.setDescription("Saved Playlist as '" + args[1] + "'.");
			}
			else
				eb.setDescription("Enter a valid Soundcloud or YouTube URL.");
			channel.sendMessage(eb.build()).queue();
		}

		else if(args[0].equalsIgnoreCase("edit") || args[0].equalsIgnoreCase("delete")){
			String[] playlist = Helper.getPlaylistbyKey(args[1]);
			if(playlist != null){
				if(Long.toString(author.getIdLong()).equals(playlist[3])){
					if(args[0].equalsIgnoreCase("edit")){
						if(args[2].toLowerCase().contains("youtube.com/") || args[2].toLowerCase().contains("soundcloud.com/")){
							Helper.deletePlaylist(args[1]);
							Helper.savePlaylist(args[1], args[2], author.getName() + "#" + author.getDiscriminator(), author.getIdLong());
							eb.setDescription("Edited Playlist '" + args[1] + "'.");
						}
						else
							eb.setDescription("Enter a valid Soundcloud or YouTube URL.");
					}
					else{
						Helper.deletePlaylist(args[1]);
						eb.setDescription("Removed Playlist '" + args[1] + "'.");
					}
				}
				else
					eb.setDescription("The owner of this playlist is `" + playlist[2] + "`.");
			}
			else
				eb.setDescription("This Playlist does not exist.");
			channel.sendMessage(eb.build()).queue();
		}

		else if(args[0].equalsIgnoreCase("info")){
			String[] playlist = Helper.getPlaylistbyKey(args[1]);
			if(playlist != null){
				eb.setDescription("Key: " + playlist[0]);
				eb.appendDescription("\nURL: " + playlist[1]);
				eb.appendDescription("\nOwner: `" + playlist[2] + "`");
			}
			else
				eb.setDescription("This Playlist does not exist.");
			channel.sendMessage(eb.build()).queue();
		}

		else if(args[0].equalsIgnoreCase("list")){
			String[] playlists = Helper.getPlaylistList();
			if(playlists != null){
				eb.setDescription("Playlists: " + playlists.length);
				int position = 1;
				for(String list : playlists){
					eb.appendDescription("\n" + position + ". `" + list + "`");
					++position;
				}
			}
			channel.sendMessage(eb.build()).queue();
		}

		else if(!msg.getAttachments().isEmpty() && requester.getVoiceState().getChannel() != null) {
			if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true))) {
				channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
				return;
			}
			checkVoiceChannel(guild.getAudioManager(), requester);
			for(Message.Attachment att : msg.getAttachments()){
				int counter = 0;
				try (BufferedReader br = new BufferedReader(new InputStreamReader(att.getInputStream()))){
					String line;
					while((line = br.readLine()) != null){
						loadAndPlay(guild, channel, line, requester, false);
						++counter;
					}
				}
				catch(IOException e){
					e.printStackTrace();
				}
				eb.setDescription(String.format("Queuing playlist `%s`\n(%d tracks)",
						att.getFileName(), counter));
				}
				channel.sendMessage(eb.build()).queue();
			}

		else{
			String[] playlist = Helper.getPlaylistbyKey(args[0]);
			if(playlist == null){
				eb.setDescription("This Playlist does not exist.");
				channel.sendMessage(eb.build()).queue();
			}
			else{
				if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true)))
					channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
				else if(args.length > 0 && requester.getVoiceState().getChannel() != null){
					checkVoiceChannel(guild.getAudioManager(), requester);
					loadAndPlay(guild, channel, playlist[1], requester, true);
				}
			}
		}
	}



	@Command(aliases = {"-playban"}, description = "Ban or unban a user from requesting songs via '-play'.\n\nRequires the \"DJ\" role.", usage = "-playban @mention", privateMessages = false)
	public void onPlaybanCommand(Guild guild, TextChannel channel, User author, Message msg){
		if(author.isBot() || (!channel.getTopic().contains("spam") && !channel.getName().contains("spam")))
			return;
		Member requester = guild.getMember(author);
		String by = "By order of `" + requester.getEffectiveName() + "`:\n";
		if(hasDJPerms(requester, channel, guild)){
			List<User> banned = msg.getMentionedUsers();
			Helper.EnsureRole(guild, Helper.ROLE_PLAYBANNED);
			List<Role> antiDJ = guild.getRolesByName(Helper.ROLE_PLAYBANNED, true);
			StringBuilder newBans = new StringBuilder("");
			StringBuilder newUnbans = new StringBuilder("");
			for(User u : banned){
				Member m = guild.getMember(u);
				if(!m.getRoles().containsAll(antiDJ)){
					guild.getController().addRolesToMember(m, antiDJ).queue();
					newBans.append(m.getEffectiveName() + "\n");
				}
				else{
					guild.getController().removeRolesFromMember(m, antiDJ).queue();
					newUnbans.append(m.getEffectiveName() + "\n");
				}
			}

			EmbedBuilder eb = new EmbedBuilder();
			eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
			eb.setColor(guild.getMember(bot.getSelf()).getColor());
			eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/info.png");
			StringBuilder output = new StringBuilder(by);
			if(newBans.toString().length() > 0){
				output.append("\tBanned from influencing the music:\n");
				output.append(newBans.toString());
				output.append("\n");
			}
			if(newUnbans.toString().length() > 0){
				output.append("\tNow allowed to influence the music:\n");
				output.append(newUnbans.toString());
			}
			eb.setDescription(output.toString());
			channel.sendMessage(eb.build()).queue();
		}
		else
			channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
	}



	@Command(aliases = {"-pause"}, description = "Pause the music player.\n\nRequires the \"DJ\" role.", usage = "-pause", privateMessages = false)
	public void onPauseCommand(Guild guild, TextChannel channel, User author, Message msg){
		if(author.isBot() || (!channel.getTopic().contains("spam") && !channel.getName().contains("spam")))
			return;
		Member requester = guild.getMember(author);
		AudioPlayerVoteHandler voteHandler = getVoteHandler(guild, "pause");
		if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true)))
			channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
		else if(canDoCommand(guild, requester)){
			if(hasDJPerms(requester, channel, guild)){
				setPauseState(true, guild, requester, channel);
				voteHandler.clear();
			}
			else if(vote(voteHandler, requester, channel, "pause"))
				setPauseState(true, guild, voteHandler.getRequester(), channel);
			msg.delete().queue();
		}
		else{
			EmbedBuilder eb = new EmbedBuilder();
			eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
			eb.setDescription("The player is not currently playing anything!");
			eb.setColor(guild.getMember(bot.getSelf()).getColor());
			eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/info.png");
			channel.sendMessage(eb.build()).queue();
			msg.delete().queue();
		}
	}



	@Command(aliases = {"-resume", "-unpause"}, description = "Un-pause the music player.\n\nRequires the \"DJ\" role.", usage = "-resume\n-unpause", privateMessages = false)
	public void onResumeCommand(Guild guild, TextChannel channel, User author, Message msg){
		if(author.isBot() || (!channel.getTopic().contains("spam") && !channel.getName().contains("spam")))
			return;
		Member requester = guild.getMember(author);
		AudioPlayerVoteHandler voteHandler = getVoteHandler(guild, "resume");
		if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true)))
			channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
		else if(canDoCommand(guild, requester)){
			if(hasDJPerms(requester, channel, guild)){
				setPauseState(false, guild, requester, channel);
				voteHandler.clear();
			}
			else if(vote(voteHandler, requester, channel, "resume"))
				setPauseState(false, guild, voteHandler.getRequester(), channel);
			msg.delete().queue();
		}
		else{
			EmbedBuilder eb = new EmbedBuilder();
			eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
			eb.setDescription("The player is not currently playing anything!");
			eb.setColor(guild.getMember(bot.getSelf()).getColor());
			eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/info.png");
			channel.sendMessage(eb.build()).queue();
			msg.delete().queue();
		}
	}



	@Command(aliases = {"-volume"}, description = "Displays the current volume or sets it to X (10-100). To change, the \"DJ\" role is required.", usage = "-volume X", privateMessages = false)
	public void onVolumeCommand(Guild guild, TextChannel channel, User author, String[] args, Message msg){
		if(author.isBot() || (!channel.getTopic().contains("spam") && !channel.getName().contains("spam")))
			return;
		Member requester = guild.getMember(author);
		String by = " by `" + requester.getEffectiveName() + "`.";
		if(requester.getRoles().containsAll(guild.getRolesByName(Helper.ROLE_PLAYBANNED, true)))
			channel.sendMessage(Helper.GetRandomDeniedMessage()).queue();
		else if(args.length == 1 && canDoCommand(guild, requester)
				&& hasDJPerms(requester, channel, guild)){
			AudioPlayer player = getGuildAudioPlayer(guild).player;
			EmbedBuilder eb = new EmbedBuilder();
			eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
			eb.setColor(guild.getMember(bot.getSelf()).getColor());
			eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/volume.png");
			try{
				int newVolume = Math.max(10, Math.min(100, Integer.parseInt(args[0])));
				int oldVolume = player.getVolume();
				player.setVolume(newVolume);
				eb.setDescription("Player volume changed from `" + oldVolume + "` to `" + newVolume + "`" + by);
				msg.delete().queue();
			}
			catch(NumberFormatException e){
				eb.setDescription("`" + args[0] + "` is not a valid integer. (10 - 100)");
			}
			channel.sendMessage(eb.build()).queue();
		}
	}



	private void loadAndPlay(Guild guild, final TextChannel channel, final String trackUrl, final Member requester, boolean sendMessage){
		GuildMusicManager musicManager = getGuildAudioPlayer(guild);

		playerManager.loadItemOrdered(musicManager, trackUrl, new AudioLoadResultHandler(){

		final String requestedby = "requested by `" + requester.getEffectiveName() + "`)";

			@Override
			public void trackLoaded(AudioTrack track){
				if(sendMessage){
					EmbedBuilder eb = new EmbedBuilder();
					eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
					eb.setDescription(String.format("Queuing `%s`[\uD83D\uDD17](%s)\n(%s",
							track.getInfo().title, trackUrl, requestedby));
					eb.setColor(guild.getMember(bot.getSelf()).getColor());
					eb.setThumbnail(Helper.getTrackThumbnail(track));
					channel.sendMessage(eb.build()).queue();
				}
				track.setUserData(requester);
				play(guild, musicManager, track);
				// If the queue is still empty, we just started playback.
				if (getGuildAudioPlayer(guild).scheduler.getQueue().isEmpty())
					setGameFromTrack(guild);
			}

			@Override
			public void playlistLoaded(AudioPlaylist playlist){
				AudioTrack firstTrack = playlist.getSelectedTrack();

				EmbedBuilder eb = new EmbedBuilder();
				eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
				eb.setColor(guild.getMember(bot.getSelf()).getColor());

				if(playlist.isSearchResult()){
					if(firstTrack == null)
						firstTrack = playlist.getTracks().get(0);
					firstTrack.setUserData(requester);
					eb.setDescription(String.format("Queuing `%s`[\uD83D\uDD17](%s)\n(first track of `%s`, %s",
							firstTrack.getInfo().title, firstTrack.getInfo().uri, playlist.getName(), requestedby));
					eb.setThumbnail(Helper.getTrackThumbnail(firstTrack));
					play(guild, musicManager, firstTrack);
					// If the queue is still empty, we just started playback.
					if (getGuildAudioPlayer(guild).scheduler.getQueue().isEmpty())
						setGameFromTrack(guild);
				}
				else{
					List<AudioTrack> tracks = playlist.getTracks();
					for (AudioTrack at : tracks)
						at.setUserData(requester);
					play(guild, musicManager, playlist);
					eb.setDescription(String.format("Queuing playlist `%s`[\uD83D\uDD17](%s)\n(%d tracks, %s",
							playlist.getName(), trackUrl, tracks.size(), requestedby));
					eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/play.png");
					//Conveniently, we don't need to call setGamefromTrack() here, since starting playback from a playlist automatically triggers onNextTrack().
				}
				if(sendMessage)
					channel.sendMessage(eb.build()).queue();
			}

			@Override
			public void noMatches(){
				EmbedBuilder eb = new EmbedBuilder();
				eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
				eb.setDescription(String.format("Nothing found by `%s`\n(%s",
						trackUrl.replace("ytsearch: ", ""), requestedby));
				eb.setColor(guild.getMember(bot.getSelf()).getColor());
				eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/cross.png");
				channel.sendMessage(eb.build()).queue();
			}

			@Override
			public void loadFailed(FriendlyException exception){
				EmbedBuilder eb = new EmbedBuilder();
				eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
				eb.setDescription("Could not play: `" + exception.getMessage() + "`\n(" + requestedby);
				eb.setColor(guild.getMember(bot.getSelf()).getColor());
				eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/info.png");
				channel.sendMessage(eb.build()).queue();
			}
		});
	}



	// Add a single song to the end of current LinkedList<AudioTrack> queue.
	private void play(Guild guild, GuildMusicManager musicManager, AudioTrack track){
		musicManager.scheduler.queue(track);
	}

	// Add a list of songs to the queue (instead of individually).
	private void play(Guild guild, GuildMusicManager musicManager, AudioPlaylist playlist){
		musicManager.scheduler.queue(playlist);
	}



	private void skipTrack(TextChannel channel, Member requester, int count){
		String requestedby = "";
		if(requester != null)
			requestedby = "\n(requested by `" + requester.getEffectiveName() + "`)";

		getGuildAudioPlayer(channel.getGuild()).scheduler.nextTrack(count);

		EmbedBuilder eb = new EmbedBuilder();
		eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
		eb.setDescription("Skipped " + (count == 1 ? "to next track."
				: "ahead " + count + " tracks.") + requestedby);
		eb.setColor(channel.getGuild().getMember(bot.getSelf()).getColor());
		eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/skip.png");
		channel.sendMessage(eb.build()).queue();
	}



	private void shuffle(Guild guild, Member requester, Message msg, TextChannel channel){
		TrackScheduler scheduler = getGuildAudioPlayer(guild).scheduler;
		if(!scheduler.getQueue().isEmpty()){
			scheduler.shuffle();
			EmbedBuilder eb = new EmbedBuilder();
			eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
			eb.setDescription("The queue has been shuffled by `" + requester.getEffectiveName() + "`!");
			eb.setColor(guild.getMember(bot.getSelf()).getColor());
			eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/shuffle.png");
			channel.sendMessage(eb.build()).queue();
			msg.delete().queue();
		}
	}



	// Pause or unpause the player.
	private void setPauseState(boolean pause, Guild guild, Member requester, TextChannel channel){
		String requestedBy = "\n(requested by `" + requester.getEffectiveName() + "`)";
		AudioPlayer player = getGuildAudioPlayer(guild).player;
		if(player.getPlayingTrack() != null && player.isPaused() != pause){
			player.setPaused(pause);
			EmbedBuilder eb = new EmbedBuilder();
			eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
			eb.setColor(guild.getMember(bot.getSelf()).getColor());
			eb.setDescription("The audio-player has been "
					+ (pause ? "paused." : "unpaused.") + requestedBy);
			eb.setThumbnail(bot.HOST_RAW_URL
					+ (pause ? "/thumbnails/pause.png" : "/thumbnails/play.png"));
			channel.sendMessage(eb.build()).queue();
		}
		else if(player.isPaused() != pause)
			channel.sendMessage("Nothing to " + (pause ? "pause." : "unpause.")).queue();
		else
			channel.sendMessage("Already " + (pause ? "paused." : "playing.")).queue();
	}



	private void stopPlayback(Guild guild, Member requester, Message msg, TextChannel channel){
		String requestedby = "\n(requested by `" + requester.getEffectiveName() + "`)";
		GuildMusicManager mng = getGuildAudioPlayer(guild);
		AudioPlayer player = mng.player;
		mng.scheduler.getQueue().clear();
		player.stopTrack();
		player.setPaused(false);
		voteHandlers.clear();
		guild.getAudioManager().closeAudioConnection();
		EmbedBuilder eb = new EmbedBuilder();
		eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
		eb.setDescription("Playback has been completely stopped and the queue has been cleared." + requestedby);
		eb.setColor(guild.getMember(bot.getSelf()).getColor());
		eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/stop.png");
		channel.sendMessage(eb.build()).queue();
		bot.setGameListening("-help");
	}



	private static String getTimestamp(long milliseconds){
		int seconds = (int) ((milliseconds / 1000) % 60);
		int minutes = (int) ((milliseconds / (1000 * 60)) % 60);
		int hours = (int) ((milliseconds / (1000 * 60 * 60)) % 24);
		int days = (int) (milliseconds / (1000 * 60 * 60 * 24));

		if(days > 0)
			return String.format("%2dd %02d:%02d:%02d", days, hours, minutes, seconds);
		else if(hours > 0)
			return String.format("%02d:%02d:%02d", hours, minutes, seconds);
		else
			return String.format("%02d:%02d", minutes, seconds);
	}



	private static void checkVoiceChannel(AudioManager am, Member requester){
		if(!am.isConnected() && !am.isAttemptingToConnect()
				&& requester.getVoiceState().getChannel() != null)
			am.openAudioConnection(requester.getVoiceState().getChannel());
	}



	private boolean hasDJPerms(Member member, TextChannel channel, Guild guild){
		if(member.getRoles().containsAll(guild.getRolesByName("DJ", true))
				|| member.getPermissions(channel).contains(Permission.ADMINISTRATOR)
				|| member.isOwner())
			return true;
		else
			return false;
	}



	// If the bot is not connected to the correct (or any) voice channel,
	// or the requester is ignored/not authorized, then return false.
	private boolean canDoCommand(Guild guild, Member requester){
		AudioManager am = guild.getAudioManager();
		if(!am.isConnected() && !am.isAttemptingToConnect())
			return false;
		if(requester.getVoiceState().getChannel() != am.getConnectedChannel())
			return false;

		return true;
	}



	/**
	 * Set up the environment for the next track being played, by clearing all
	 * vote handlers and updating the "help" text for James.
	 * 
	 * @param Guild guild   The guild with the playing track.
	 */
	public void onNextTrack(Guild guild){
		voteHandlers.clear();
		setGameFromTrack(guild);
	}



	private void setGameFromTrack(Guild guild){
		//Note: This will be buggy should James play music in multiple guilds at once; since James is only active in one Guild atm, it shouldn't be a problem (for now).
		try{
			bot.setGamePlaying(getGuildAudioPlayer(guild).player.getPlayingTrack().getInfo().title);
		}
		catch(NullPointerException e){
			bot.setGameListening("-help");
		}
	}



	private synchronized boolean vote(AudioPlayerVoteHandler handler, Member requester, TextChannel channel, String subject){
		int previousVotes = handler.getVotes();
		handler.vote(requester);
		int currentVotes = handler.getVotes();
		if(handler.checkVotes())
			return true;
		else if(previousVotes != currentVotes){
			EmbedBuilder eb = new EmbedBuilder();
			eb.setTitle("Audio-Player:", "https://github.com/sedmelluq/lavaplayer");
			eb.setColor(channel.getGuild().getMember(bot.getSelf()).getColor());
			eb.setDescription(String.format("Currently are %d captains voting to %s, but %d are needed to %s!\n(Voters:%s)",
					currentVotes, subject, handler.getRequiredVotes(), subject, handler.getVoters(", ")));
			eb.setThumbnail(bot.HOST_RAW_URL + "/thumbnails/vote.png");
			channel.sendMessage(eb.build()).queue();
		}
		return false;
	}



	private void printQueue(TextChannel channel, String queueName){
		try {
			StringBuilder sb = new StringBuilder();
			sb.append(getGuildAudioPlayer(channel.getGuild()).player.getPlayingTrack().getInfo().uri + "\n");
			LinkedList<AudioTrack> queue = getGuildAudioPlayer(channel.getGuild()).scheduler.getQueue();
			for (AudioTrack at : queue)
				sb.append(at.getInfo().uri + "\n");
			if(!queueName.equals(""))
				channel.sendFile(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8.name())), "queue" + queueName + ".txt", null).queue();
			else
				channel.sendFile(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8.name())), "queue.txt", null).queue();
		}
		catch(IOException e) {
			e.printStackTrace();
		}
	}



	private synchronized AudioPlayerVoteHandler getVoteHandler(Guild guild, String key){
		if(voteHandlers.containsKey(key))
			return voteHandlers.get(key);
		else{
			AudioPlayerVoteHandler handler = new AudioPlayerVoteHandler(guild);
			voteHandlers.put(key, handler);
			return handler;
		}
	}



	private static String NowPlayingInfo(AudioTrack track){
		return String.format("**Playing:** %s [\uD83D\uDD17](%s)\n**Time:** [%s / %s]",
				track.getInfo().title,
				track.getInfo().uri,
				getTimestamp(track.getPosition()),
				getTimestamp(track.getDuration()));
	}
}
