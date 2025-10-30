#!/usr/bin/env python3
"""
Transmission Time Calculator
Shows before/after transmission times with optimized chunk delays
"""

def calculate_transmission_times():
    # From audio analysis results
    files = {
        'hugo_en.wav': {'size_mb': 9.54, 'duration_sec': 52, 'chunks': 4884},
        'kira_en.wav': {'size_mb': 9.41, 'duration_sec': 51, 'chunks': 4816},
        'hugo_fr.wav': {'size_mb': 11.12, 'duration_sec': 66, 'chunks': 5693},
        'kira_fr.wav': {'size_mb': 11.71, 'duration_sec': 69, 'chunks': 5996}
    }
    
    print("⏰ TRANSMISSION TIME COMPARISON")
    print("=" * 60)
    
    for filename, data in files.items():
        lang = 'French' if 'fr' in filename else 'English'
        actual_duration = data['duration_sec']
        chunks = data['chunks']
        
        # Old timing (64ms per chunk)
        old_transmission = chunks * 64 / 1000  # seconds
        old_minutes = old_transmission / 60
        
        # New timing (11ms for English, 12ms for French)
        new_delay = 12 if 'fr' in filename else 11
        new_transmission = chunks * new_delay / 1000  # seconds
        new_minutes = new_transmission / 60
        
        print(f"\n🎵 {filename} ({lang})")
        print(f"   📏 Actual audio duration: {actual_duration} seconds ({actual_duration/60:.1f} minutes)")
        print(f"   📊 Total chunks: {chunks:,}")
        print(f"   ❌ OLD (64ms/chunk): {old_transmission:.1f}s ({old_minutes:.1f} min) - {old_transmission/actual_duration:.1f}x slower!")
        print(f"   ✅ NEW ({new_delay}ms/chunk): {new_transmission:.1f}s ({new_minutes:.1f} min) - {new_transmission/actual_duration:.1f}x real-time")
        
        # Speed improvement
        speedup = old_transmission / new_transmission
        print(f"   🚀 Speed improvement: {speedup:.1f}x faster!")

if __name__ == "__main__":
    calculate_transmission_times()