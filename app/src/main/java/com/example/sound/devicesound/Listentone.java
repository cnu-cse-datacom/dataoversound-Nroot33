package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;

import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.abs;
import static java.lang.Math.min;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;

    public Listentone(){
        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();
    }

    private double findFrequency(double[] toTransform) { // dominant
        int len = toTransform.length;
        double[] real = new double[len];
        double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];

        Complex[] complx = transform.transform(toTransform, TransformType.FORWARD); //fft 복소수
        Double[] freq = this.fftfreq(complx.length, 1); //fftfreq

        for (int i = 0; i < complx.length; i++) {
            realNum = complx[i].getReal();
            imgNum = complx[i].getImaginary();
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum));  //복소수를 크기로 바꿔주는 거임 => mag 배열 python에서 w
        }
        double peak_coeff =0;
        int index = 0;
        for(int i =0; i<complx.length; i++){
            if(peak_coeff<mag[i]) {
                peak_coeff = mag[i];
                index = i;
            }
        }
        Double peak_freq = freq[index];
        return abs(peak_freq*mSampleRate);
    }

    private Double[] fftfreq(int length, int d) {
        double val = 1.0 / (length * d);
        int[] results = new int[length];
        Double[] d_results = new Double[length];
        int N = (length-1)/2 + 1;
        for(int i=0; i<=N; i++){
            results[i] = i;
        }
        int temp = -(length / 2);
        for(int i=N+1; i<length; i++) {
            results[i] = temp;
            temp--;
        }
        for(int i = 0; i<length; i++){
            d_results[i] = results[i] * val;
        }
        return d_results;
    }

    private int findPowersize(int round) { //findPowersize
        int temp = 1;
        while(true) {
                temp = temp*2;
                if(temp >= round)
                    return temp;
            }
        }

    public boolean match(double freq1, double freq2) { //match
        return abs(freq1 - freq2) < 20 ;
    }

    public List<Integer> decode_bitchunks(int chunk_bits, List<Integer> new_bit_chunks){ // decode_bitchunks
    List<Integer> out_bytes = new ArrayList<>();
    int next_read_chunk = 0 ;
    int next_read_bit = 0 ;
    int _byte = 0;
    int bits_left = 8 ;

            while(next_read_chunk < new_bit_chunks.size()) {
                int can_fill = chunk_bits - next_read_bit;
                int to_fill = min(bits_left, can_fill);
                int offset = chunk_bits - next_read_bit - to_fill;
                _byte <<= to_fill;
                int shifted = new_bit_chunks.get(next_read_chunk) & (((1 << to_fill) - 1) << offset);
                _byte |= shifted >> offset;
                bits_left -= to_fill;
                next_read_bit += to_fill;

                if (bits_left <= 0) {
                    out_bytes.add(_byte);
                    _byte = 0;
                    bits_left = 8;
                }

                if (next_read_bit >= chunk_bits){
                next_read_chunk += 1;
                next_read_bit -= chunk_bits;
                }
            }
    return out_bytes  ;
        }

     public List<Integer> extract_packet(List<Double> freqs) { // extract_packet
        List<Double> half_freq = new ArrayList<>();
        List<Integer> bit_chucks = new ArrayList<>();
        List<Integer> new_bit_chucks = new ArrayList<>();

        for(int i =0; i<freqs.size(); i=i+1){
            double temp = freqs.get(i);
            half_freq.add(temp);
        }

        for(int i= 0; i<half_freq.size(); i++){
            int temp = (int)(Math.round((half_freq.get(i) -START_HZ)/STEP_HZ));
            bit_chucks.add(temp);
        }

        for(int i=1; i<bit_chucks.size(); i++) {
            if (bit_chucks.get(i) > 0 && bit_chucks.get(i) < 16) {
                new_bit_chucks.add(bit_chucks.get(i));
            }
        }
        List<Integer> decode_bits = decode_bitchunks(BITS, new_bit_chucks);
         return decode_bits;
     }

    public void PreRequest() { //listen_linux
        int blocksize = findPowersize((int) (long) Math.round(interval / 2 * mSampleRate));
        short[] buffer = new short[blocksize]; //chuck
        double[] trans = new double[blocksize]; // chuck = buffer -> trans
        List<Double> stream = new ArrayList<>();
        List<Integer> byte_stream = new ArrayList<>();

        while (true) {
            int bufferedReadResult = mAudioRecord.read(buffer, 0, blocksize);
            if(bufferedReadResult<0)
                continue;
            for(int i =0; i<blocksize; i++)
                trans[i] = buffer[i];

            double dom = findFrequency(trans);

            if(startFlag && match(dom,HANDSHAKE_END_HZ)) {
                byte_stream = extract_packet(stream);
                Log.d("byte_stream", byte_stream.toString());
                String result = "";
                for(int index = 0 ; index < byte_stream.size() ; index++){
                    result += Character.toString((char)((int)byte_stream.get(index)));
                }
                Log.d("result", result.toString());
                stream.clear();
                startFlag=false;
            }
            else if(startFlag)
                stream.add(dom);
            else if(match(dom,HANDSHAKE_START_HZ))
                startFlag =true;
            Log.d("dom", ""+dom);

        }
    }
}

