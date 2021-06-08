package be.panako.syncsink;

import java.awt.Dimension;
import java.awt.Toolkit;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.UIManager;

import be.panako.cli.Store;
import be.panako.strategy.QueryResult;
import be.panako.strategy.QueryResultHandler;
import be.panako.strategy.olaf.OlafStrategy;
import be.panako.util.Config;
import be.panako.util.Key;
import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.PipeDecoder;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.io.jvm.AudioPlayer;
import be.tarsos.dsp.io.jvm.JVMAudioInputStream;

public class SyncSink {
	
	private final OlafStrategy strategy;
	private final File reference;
	private final List<File> others;
	private final List<QueryResult> otherResults;
	
	private QueryResult temp;
	
	public SyncSink(File reference) {
		//Store and match the files in memory
		//do not use the on disk key value store
		Config.set(Key.OLAF_STORAGE,"MEM");
		
		strategy = new OlafStrategy();
		
		//makes sure the memory db is empty
		strategy.clear();
		
		strategy.store(reference.getPath(),"");
		
		this.reference = reference;
		
		others=new ArrayList<>();
		otherResults=new ArrayList<>();
	}
	
	
	
	public QueryResult syncReferenceWithOther(File other) {
		
		strategy.query(other.getPath(), 1, new HashSet<Integer>(), new QueryResultHandler() {
			public void handleQueryResult(QueryResult result) {
				temp =  result;
			}
			
			public void handleEmptyResult(QueryResult result) {
				temp = null;
			}
		});
		
		others.add(other);
		otherResults.add(temp);
		
		return temp;
	}
	
	public String synchronizeMedia(){
		//float referenceFileDuration = getMediaDuration(streamFiles.get(0).getAbsolutePath());
		//String commandFile = reference.getParent() + File.separator + "sync_ffmpeg_commands.bash";
		
		StringBuilder sb = new StringBuilder();
		sb.append("\n");
		sb.append("#execute the following in the terminal \n");
		sb.append("cd '").append(reference.getParent()).append("'\n");
		
		String refName = reference.getName();
		boolean refIsVideo = refName.matches("(?i).*(mpg|avi|mp4|mkv|mpeg)");
		if(!refIsVideo && refName.contains(".")) {
			
			String extension = refName.substring(refName.lastIndexOf(".") + 1);
			
			String refWav = "original_" + refName.replace(extension,"wav");
			
			sb.append("ffmpeg -i \"" + refName +  "\" \"" + refWav + "\"").append("\n");
		}
		
		
		for(int i = 0 ; i < others.size() ; i++){
			//float otherFileDuration = getMediaDuration(streamFiles.get(i).getAbsolutePath());
			File otherFile = others.get(i);
			QueryResult match = otherResults.get(i);
			
			//skip files without results
			if(match == null)
				continue;
			
			boolean isVideo = otherFile.getName().matches("(?i).*(mpg|avi|mp4|mkv|mpeg)");
			
			float guessedStartTimeOfStream = getRefinedOffset(i);
			String command;
			if(guessedStartTimeOfStream >= 0){		
				if(isVideo){
					command = "# command to add black frames to video to sync here: consider using the shortest file as reference";
					sb.append(command).append("\n");
				}else{
					//add silence
					String syncedmediaFile = "synced_" + otherFile.getName();
					
					if(syncedmediaFile.contains(".")) {
						String extension = syncedmediaFile.substring(syncedmediaFile.lastIndexOf(".") + 1);
						syncedmediaFile = syncedmediaFile.replace(extension, "wav");					
					}
					command = "ffmpeg -f lavfi -i aevalsrc=0:d="+guessedStartTimeOfStream+" -i  \"" + otherFile +  "\"  -filter_complex \"[0:0] [1:0] concat=n=2:v=0:a=1 [a]\" -map [a] \"" + syncedmediaFile + "\"";
					sb.append(command).append("\n");;
				}
			}else{
				//cut the first part
				String startString = String.format("%.3f", -1 * guessedStartTimeOfStream);
				String syncedmediaFile = "synced_" + otherFile.getName();
				
				if(!isVideo && syncedmediaFile.contains(".")) {
					String extension = syncedmediaFile.substring(syncedmediaFile.lastIndexOf(".") + 1);
					syncedmediaFile = syncedmediaFile.replace(extension, "wav");					
				}
				
				//the same command for audio and video
				command = "ffmpeg -ss " + startString + " -i \"" + otherFile +  "\" \"" + syncedmediaFile + "\"";
				sb.append(command).append("\n");
			}
		}
		return sb.toString();
	}
	
	public float getOffset(int otherIndex) {
		QueryResult match = otherResults.get(otherIndex);
		if(match == null) {
			return 0;
		}else {
			return (float) (match.refStart - match.queryStart);
		}
	}
	
	public float getRefinedOffset(int otherIndex) {
		
		float duration = 1.0f;
		float estimatedOffset = getOffset(otherIndex);
		
		float startInReference;
		float startInQuery;
		
		if(estimatedOffset > 0) {
			startInReference = estimatedOffset + 0.1f;
			startInQuery = startInReference - estimatedOffset - 0.041f;
		}else {
			startInQuery = -estimatedOffset + 0.1f;
			startInReference = startInQuery + estimatedOffset - 0.041f;
		}
		
		CrossCorrelation cc = new CrossCorrelation(reference.getAbsolutePath(), startInReference, duration);
		return cc.correlateWith(others.get(otherIndex).getAbsolutePath(), startInQuery);
	}
	
	public void playReferenceAndOther(int otherIndex) {
		
		float offset = getOffset(otherIndex);
		float otherDuration = (float) new PipeDecoder().getDuration(others.get(otherIndex).getAbsolutePath());
		int sr = 44100;
		final int numberOfSamples = (int) Math.min(25 *  sr , otherDuration * sr);
		final float[] syncedAudio = new float[numberOfSamples];
		final float[] referenceAudio = new float[numberOfSamples];
		final float[] mixedAudio = new float[numberOfSamples];
		
		File otherFile = others.get(otherIndex);
		
		AudioDispatcher adp = AudioDispatcherFactory.fromPipe(otherFile.getAbsolutePath(), sr, numberOfSamples, 0, offset > 0 ? 0 : -1 * offset/1000.0 );
		adp.addAudioProcessor(new AudioProcessor() {
			boolean first = true;
			@Override
			public void processingFinished() {}
			
			@Override
			public boolean process(AudioEvent audioEvent) {
				if(first){
					float[] buffer = audioEvent.getFloatBuffer();
					for(int i = 0 ; i < numberOfSamples ; i++){
						syncedAudio[i] = buffer[i];
					}
					first = false;
				}
				return true;
			}
		});
		adp.run();
		
		adp = AudioDispatcherFactory.fromPipe(reference.getAbsolutePath(), sr, numberOfSamples, 0,offset > 0 ? offset/1000.0 : 0);
		
		adp.addAudioProcessor(new AudioProcessor() {
			boolean first = true;
			@Override
			public void processingFinished() {}
			
			@Override
			public boolean process(AudioEvent audioEvent) {
				if(first){
					float[] buffer = audioEvent.getFloatBuffer();
					for(int i = 0 ; i < numberOfSamples ; i++){
						referenceAudio[i] = buffer[i];
					}
					first = false;
				}
				return true;
			}
		});
		adp.run();
		
		for(int i = 0 ; i < numberOfSamples ; i++){
			float gain = 1.0f;
			//fade in
			if(i < 1000){
				gain = i/1000.0f;
			}
			//fade out
			if(i > numberOfSamples - 1000){
				gain = (float)(numberOfSamples-i)/1000.0f;
			}
			float sourceFactor = i/((float)numberOfSamples);//goes from 0 to 1 five times
			
			mixedAudio[i] = gain * (sourceFactor * syncedAudio[i] + (1-sourceFactor) * referenceAudio[i]);
		}
		
		try {
			adp = AudioDispatcherFactory.fromFloatArray(mixedAudio, sr, 2048, 0);
			adp.addAudioProcessor(new AudioPlayer(JVMAudioInputStream.toAudioFormat(adp.getFormat())));
			new Thread(adp,"Audio play thread for " + otherFile.getName()).start();
		} catch (UnsupportedAudioFileException e2) {
			e2.printStackTrace();
		} catch (LineUnavailableException e1) {
			e1.printStackTrace();
		}
	}
	
	public static void main(String[] args) {
		//makes sure number formatting is always the same
		java.util.Locale.setDefault(Locale.US);
		
		javax.swing.SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				try {
					UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				} catch (Exception e) {
				}
				SyncSinkFrame frame = new SyncSinkFrame();
				frame.setSize(800, 600);
				frame.setVisible(true);
				Dimension dim = Toolkit.getDefaultToolkit().getScreenSize();
				frame.setLocation(dim.width/2-frame.getSize().width/2, dim.height/2-frame.getSize().height/2);
				
				//add the files
				int i = 0;
				for(File file : new Store().getFilesFromArguments(args)){
					frame.openFile(file,i);
					i++;
				}
			}
		});		
	}

}
