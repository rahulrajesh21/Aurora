import 'dotenv/config';
import https from 'https';

async function debug() {
    console.log('--- Last.fm Debug Script ---');

    const apiKey = process.env.LASTFM_API_KEY;

    if (!apiKey) {
        console.error('ERROR: LASTFM_API_KEY is not set in the environment.');
        console.error('Usage: LASTFM_API_KEY=your_key_here npx ts-node src/scripts/debugLastFm.ts');
        return;
    }

    console.log('API Key found (length: ' + apiKey.length + ')');

    const baseUrl = 'https://ws.audioscrobbler.com/2.0/';
    const tags = ['new releases', '2024', 'trending', 'pop'];

    for (const tag of tags) {
        console.log(`\n\n--- Testing Tag: ${tag} ---`);
        const params = new URLSearchParams({
            method: 'tag.gettopalbums',
            tag: tag,
            api_key: apiKey,
            format: 'json',
            limit: '3'
        });

        const url = `${baseUrl}?${params.toString()}`;
        console.log(`Fetching: ${url.replace(apiKey, 'HIDDEN')}`);

        try {
            const response = await fetch(url);
            console.log(`Status: ${response.status}`);

            if (!response.ok) {
                console.error(`Error fetching tag ${tag}: ${response.statusText}`);
                continue;
            }

            const data = (await response.json()) as any;
            const items = data.albums?.album || [];
            console.log(`Found ${items.length} items.`);

            if (items.length > 0) {
                items.forEach((item: any, index: number) => {
                    console.log(`\nItem ${index + 1}: ${item.name} by ${item.artist?.name || item.artist}`);
                    if (item.image && item.image.length > 0) {
                        const img = item.image.find((i: any) => i.size === 'extralarge') || item.image[item.image.length - 1];
                        console.log(`Image URL: ${img['#text']}`);
                    } else {
                        console.log('No image found.');
                    }
                });
            } else {
                console.warn('No items returned.');
            }
        } catch (error) {
            console.error(`Exception fetching tag ${tag}:`, error);
        }
    }
}

debug();
