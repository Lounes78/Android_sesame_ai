#!/usr/bin/env python3
"""
Chunk Timing Calculation - Shows exactly how 11ms was computed
"""

def calculate_chunk_timing():
    print("🔢 CHUNK TIMING CALCULATION")
    print("=" * 50)
    
    # From audio analysis results
    files = {
        'kira_en.wav': {'duration_sec': 51, 'chunks': 4816},
        'hugo_en.wav': {'duration_sec': 52, 'chunks': 4884},
        'kira_fr.wav': {'duration_sec': 69, 'chunks': 5996}, 
        'hugo_fr.wav': {'duration_sec': 66, 'chunks': 5693}
    }
    
    print("\n📊 CALCULATION FORMULA:")
    print("   Time per chunk = Audio duration ÷ Number of chunks")
    print("   Goal: Send audio in real-time (1x speed)")
    
    english_times = []
    french_times = []
    
    for filename, data in files.items():
        duration = data['duration_sec']
        chunks = data['chunks']
        time_per_chunk_ms = (duration / chunks) * 1000  # Convert to milliseconds
        
        print(f"\n🎵 {filename}:")
        print(f"   Duration: {duration} seconds")
        print(f"   Chunks: {chunks:,}")
        print(f"   Time per chunk: {duration} ÷ {chunks} = {time_per_chunk_ms:.2f}ms")
        
        if 'en' in filename:
            english_times.append(time_per_chunk_ms)
        else:
            french_times.append(time_per_chunk_ms)
    
    # Calculate averages
    avg_english = sum(english_times) / len(english_times)
    avg_french = sum(french_times) / len(french_times)
    
    print(f"\n📈 AVERAGES:")
    print(f"   English files: {avg_english:.2f}ms per chunk")
    print(f"   French files:  {avg_french:.2f}ms per chunk")
    
    print(f"\n🎯 ROUNDED VALUES USED IN CODE:")
    print(f"   English: {round(avg_english)}ms per chunk")
    print(f"   French:  {round(avg_french)}ms per chunk")
    
    print(f"\n✅ This ensures 1:1 real-time audio transmission!")

if __name__ == "__main__":
    calculate_chunk_timing()