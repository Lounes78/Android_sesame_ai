#!/usr/bin/env python3
"""
Audio Analysis Script for Sesame AI Android App
Analyzes differences between English and French audio prompts
"""

import os
import wave
import struct
from pathlib import Path

def analyze_wav_file(file_path):
    """Analyze a WAV file and return its properties"""
    try:
        with wave.open(str(file_path), 'rb') as wav_file:
            # Basic WAV properties
            frames = wav_file.getnframes()
            sample_rate = wav_file.getframerate()
            channels = wav_file.getnchannels()
            sample_width = wav_file.getsampwidth()
            duration = frames / sample_rate
            
            # File size
            file_size = file_path.stat().st_size
            
            # Calculate bitrate
            bitrate = (sample_rate * channels * sample_width * 8) / 1000  # kbps
            
            # Read a small sample to check for data issues
            wav_file.setpos(0)
            sample_data = wav_file.readframes(min(1024, frames))
            
            return {
                'file_name': file_path.name,
                'file_size_mb': file_size / (1024 * 1024),
                'file_size_bytes': file_size,
                'duration_seconds': duration,
                'sample_rate': sample_rate,
                'channels': channels,
                'sample_width_bytes': sample_width,
                'bit_depth': sample_width * 8,
                'total_frames': frames,
                'bitrate_kbps': bitrate,
                'data_sample': len(sample_data),
                'avg_bytes_per_second': file_size / duration if duration > 0 else 0
            }
    except Exception as e:
        return {
            'file_name': file_path.name,
            'error': str(e)
        }

def format_size(bytes_size):
    """Format bytes to human readable size"""
    for unit in ['B', 'KB', 'MB', 'GB']:
        if bytes_size < 1024.0:
            return f"{bytes_size:.2f} {unit}"
        bytes_size /= 1024.0
    return f"{bytes_size:.2f} TB"

def format_duration(seconds):
    """Format duration to MM:SS"""
    minutes = int(seconds // 60)
    secs = int(seconds % 60)
    return f"{minutes}:{secs:02d}"

def main():
    # Define the assets directory path
    assets_dir = Path("app/src/main/assets")
    
    if not assets_dir.exists():
        print(f"❌ Assets directory not found: {assets_dir}")
        print("Please run this script from the project root directory.")
        return
    
    print("🎵 SESAME AI AUDIO ANALYSIS 🎵")
    print("=" * 50)
    
    # Find all WAV files
    wav_files = list(assets_dir.glob("*.wav"))
    
    if not wav_files:
        print("❌ No WAV files found in assets directory")
        return
    
    print(f"Found {len(wav_files)} audio files:")
    
    # Analyze each file
    analysis_results = []
    for wav_file in sorted(wav_files):
        print(f"\n📁 Analyzing: {wav_file.name}")
        result = analyze_wav_file(wav_file)
        analysis_results.append(result)
        
        if 'error' in result:
            print(f"   ❌ Error: {result['error']}")
        else:
            print(f"   📊 Size: {format_size(result['file_size_bytes'])}")
            print(f"   ⏱️  Duration: {format_duration(result['duration_seconds'])}")
            print(f"   🔊 Sample Rate: {result['sample_rate']:,} Hz")
            print(f"   🎚️  Bit Depth: {result['bit_depth']} bit")
            print(f"   📻 Channels: {result['channels']}")
            print(f"   💾 Bitrate: {result['bitrate_kbps']:.1f} kbps")
    
    # Compare English vs French files
    print("\n" + "=" * 50)
    print("📊 COMPARISON ANALYSIS")
    print("=" * 50)
    
    english_files = [r for r in analysis_results if 'en' in r['file_name'] and 'error' not in r]
    french_files = [r for r in analysis_results if 'fr' in r['file_name'] and 'error' not in r]
    
    if english_files and french_files:
        print("\n🇺🇸 ENGLISH FILES:")
        for file in english_files:
            print(f"   {file['file_name']}: {format_size(file['file_size_bytes'])} | {format_duration(file['duration_seconds'])} | {file['sample_rate']:,}Hz")
        
        print("\n🇫🇷 FRENCH FILES:")
        for file in french_files:
            print(f"   {file['file_name']}: {format_size(file['file_size_bytes'])} | {format_duration(file['duration_seconds'])} | {file['sample_rate']:,}Hz")
        
        # Calculate averages
        avg_en_size = sum(f['file_size_bytes'] for f in english_files) / len(english_files)
        avg_fr_size = sum(f['file_size_bytes'] for f in french_files) / len(french_files)
        avg_en_duration = sum(f['duration_seconds'] for f in english_files) / len(english_files)
        avg_fr_duration = sum(f['duration_seconds'] for f in french_files) / len(french_files)
        
        print(f"\n📈 AVERAGES:")
        print(f"   English: {format_size(avg_en_size)} | {format_duration(avg_en_duration)}")
        print(f"   French:  {format_size(avg_fr_size)} | {format_duration(avg_fr_duration)}")
        
        size_ratio = avg_fr_size / avg_en_size if avg_en_size > 0 else 0
        duration_ratio = avg_fr_duration / avg_en_duration if avg_en_duration > 0 else 0
        
        print(f"\n🔍 RATIOS:")
        print(f"   French files are {size_ratio:.1f}x larger in size")
        print(f"   French files are {duration_ratio:.1f}x longer in duration")
        
        # Transmission time estimate (64ms per 2048-byte chunk)
        chunk_size = 2048  # bytes
        chunk_delay_ms = 64
        
        print(f"\n⏰ ESTIMATED TRANSMISSION TIMES (at {chunk_delay_ms}ms per {chunk_size}-byte chunk):")
        for file in english_files:
            chunks = file['file_size_bytes'] / chunk_size
            transmission_time = chunks * chunk_delay_ms / 1000  # seconds
            print(f"   {file['file_name']}: {transmission_time:.1f} seconds ({chunks:.0f} chunks)")
        
        for file in french_files:
            chunks = file['file_size_bytes'] / chunk_size
            transmission_time = chunks * chunk_delay_ms / 1000  # seconds
            print(f"   {file['file_name']}: {transmission_time:.1f} seconds ({chunks:.0f} chunks)")
    
    print(f"\n✅ Analysis complete!")

if __name__ == "__main__":
    main()