/***************************************************************************
*                                                                          *
* Panako - acoustic fingerprinting                                         *
* Copyright (C) 2014 - 2017 - Joren Six / IPEM                             *
*                                                                          *
* This program is free software: you can redistribute it and/or modify     *
* it under the terms of the GNU Affero General Public License as           *
* published by the Free Software Foundation, either version 3 of the       *
* License, or (at your option) any later version.                          *
*                                                                          *
* This program is distributed in the hope that it will be useful,          *
* but WITHOUT ANY WARRANTY; without even the implied warranty of           *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the            *
* GNU Affero General Public License for more details.                      *
*                                                                          *
* You should have received a copy of the GNU Affero General Public License *
* along with this program.  If not, see <http://www.gnu.org/licenses/>     *
*                                                                          *
****************************************************************************
*    ______   ________   ___   __    ________   ___   ___   ______         *
*   /_____/\ /_______/\ /__/\ /__/\ /_______/\ /___/\/__/\ /_____/\        *
*   \:::_ \ \\::: _  \ \\::\_\\  \ \\::: _  \ \\::.\ \\ \ \\:::_ \ \       *
*    \:(_) \ \\::(_)  \ \\:. `-\  \ \\::(_)  \ \\:: \/_) \ \\:\ \ \ \      *
*     \: ___\/ \:: __  \ \\:. _    \ \\:: __  \ \\:. __  ( ( \:\ \ \ \     *
*      \ \ \    \:.\ \  \ \\. \`-\  \ \\:.\ \  \ \\: \ )  \ \ \:\_\ \ \    *
*       \_\/     \__\/\__\/ \__\/ \__\/ \__\/\__\/ \__\/\__\/  \_____\/    *
*                                                                          *
****************************************************************************
*                                                                          *
*                              Panako                                      *
*                       Acoustic Fingerprinting                            *
*                                                                          *
****************************************************************************/

package be.panako.syncsink;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.UnsupportedAudioFileException;

import be.panako.strategy.QueryResult;
import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.util.fft.FFT;

public class CrossCorrelation{
	
	private final float[][] referenceAudioData;
	private final int sampleRate;
	private final int durationInSamples;
	private final float startInRef;
	private final int amountOfAudioBlocks;
	
	public CrossCorrelation(String referenceAudioFileName, float startInRef,float duration){
		this.sampleRate = 4000;
		this.amountOfAudioBlocks = 10;
				
		this.durationInSamples = Math.round(sampleRate*duration);
		this.referenceAudioData = toArray(referenceAudioFileName,startInRef,amountOfAudioBlocks);
		this.startInRef=startInRef;
	}
	
	public float correlateWith(String otherAudioFileName, float startInOther) {
		float[][] queryAudioData = toArray(otherAudioFileName,startInOther,amountOfAudioBlocks);
		
		List<Float> offsetEstimates = new ArrayList<>();
		for(int i = 0 ; i < queryAudioData.length ; i++ ) {
			
			int maxIndex = crossCorrelation(queryAudioData[i], referenceAudioData[i]);
			
			if(maxIndex > referenceAudioData[i].length/2) {
				maxIndex = referenceAudioData[i].length - maxIndex;
				maxIndex = -maxIndex;
			}
			double crossCorrOffset = maxIndex / (double) sampleRate + (startInOther - startInRef);
			double estimatedOffset = (startInOther - startInRef);
			double range = 0.064;
			//System.out.printf("%d max index %d  offset: %.3f \n",i,maxIndex,crossCorrOffset);
			if(Math.abs(estimatedOffset - crossCorrOffset) < range ) {
				offsetEstimates.add((float) crossCorrOffset);
			}
		}
		
		return -getAverage(offsetEstimates);
	}
	
	private int crossCorrelation(float[] one, float[] other) {
		int maxIndex = -1;
		double maxValue=-1000;
		
		for(int lag = 0 ; lag < one.length ; lag++) {
			double accum = 0;
			for(int i = 0 ; i < other.length ; i++) {
				accum += other[i] * one[(i+lag) % other.length];
			}
			if(accum > maxValue) {
				maxValue = accum;
				maxIndex = lag;
			}
		}
		return maxIndex;
	}
	
	private float getAverage(List<Float> list) {
		float sum = 0;
		for(Float f : list) {
			sum+=f;
		}
		return sum/(float) list.size();
	}
	
	
	private float[][] toArray(String audioFileName,float startTime,int amountOfBlocks) {
		float[][] audioData = new float[amountOfBlocks][durationInSamples];
		
		AudioDispatcher q = AudioDispatcherFactory.fromPipe(audioFileName, sampleRate, durationInSamples, 0,startTime);
		q.addAudioProcessor(new AudioProcessor() {
			
			int block = 0;
			@Override
			public boolean process(AudioEvent audioEvent) {
				if(block < audioData.length) {
					float[] src = audioEvent.getFloatBuffer();
					System.arraycopy(src, 0, audioData[block], 0, durationInSamples);
					block++;
				}
				return true;
			}
			@Override
			public void processingFinished() {
			}
		});
		q.run();		
		return audioData;
	}

	
	public static void main(String...strings) throws UnsupportedAudioFileException, IOException{
		String reference = "/Users/joren/Desktop/track_02.m4a";
		String other = "/Users/joren/Desktop/track_02_7s.mp3";
		
		SyncSink ss = new SyncSink(new File(reference));
		QueryResult r = ss.syncReferenceWithOther(new File(other));
		
		float duration = 1.0f;
		float estimatedOffset = ss.getOffset(0);
		
		float startInReference;
		float startInQuery;
		
		if(estimatedOffset > 0) {
			startInReference = estimatedOffset + 0.1f;
			startInQuery = startInReference - estimatedOffset - 0.041f;
		}else {
			startInQuery = -estimatedOffset + 0.1f;
			startInReference = startInQuery + estimatedOffset - 0.041f;
		}
		
		CrossCorrelation cc = new CrossCorrelation(reference, startInReference, duration);
		float offset = cc.correlateWith(other, startInQuery);
		System.out.printf("%.3f \n",offset);
	}
}
