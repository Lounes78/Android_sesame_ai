#!/usr/bin/env python3
"""
Chunk Count Explanation - Shows how the number of chunks was calculated
"""

def explain_chunk_calculation():
    print("📦 CHUNK COUNT CALCULATION")
    print("=" * 50)
    
    # From the audio analysis results
    files = {
        'hugo_en.wav': {'size_mb': 9.54, 'size_bytes': 9_999_898},
        'kira_en.wav': {'size_mb': 9.41, 'size_bytes': 9_869_414}, 
        'hugo_fr.wav': {'size_mb': 11.12, 'size_bytes': 11_661_162},
        'kira_fr.wav': {'size_mb': 11.71, 'size_bytes': 12_279_286}
    }
    
    # Chunk size used by the Android app
    CHUNK_SIZE_BYTES = 2048
    
    print(f"📊 FORMULA:")
    print(f"   Number of chunks = File size in bytes ÷ {CHUNK_SIZE_BYTES} bytes per chunk")
    print(f"   (This is the chunk size used in AudioFileProcessor)")
    
    for filename, data in files.items():
        size_bytes = data['size_bytes']
        size_mb = data['size_mb']
        
        # Calculate number of chunks
        num_chunks = size_bytes / CHUNK_SIZE_BYTES
        num_chunks_rounded = round(num_chunks)
        
        print(f"\n🎵 {filename}:")
        print(f"   File size: {size_mb} MB = {size_bytes:,} bytes")
        print(f"   Calculation: {size_bytes:,} ÷ {CHUNK_SIZE_BYTES} = {num_chunks:.1f}")
        print(f"   Number of chunks: {num_chunks_rounded:,}")
    
    print(f"\n💡 WHY 2048 BYTES PER CHUNK?")
    print(f"   This is the chunk size defined in the Android AudioFileProcessor")
    print(f"   Each chunk gets sent individually over the WebSocket connection")
    print(f"   The old code waited 64ms between each chunk")
    print(f"   The new code waits 11ms (English) or 12ms (French) between chunks")

if __name__ == "__main__":
    explain_chunk_calculation()