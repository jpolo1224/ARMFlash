package com.armsx.flashpoint.ui

/**
 * The Games tab's first screen: well-known titles before the A to Z list, picked by jpolo1224
 * (Papa's games, Gravity Guy, Poptropica, Fancy Pants, Super Mario 63, Ultimate Flash Sonic,
 * Ninja Painter, Duck Life, Henry Stickmin, Happy Wheels, Run, Fireboy & Watergirl by Oslo Albet
 * and Jan Villanueva, Learn to Fly by Light Bringer, Jetpack Joyride, Angry Birds, Plants vs.
 * Zombies 1 and 2, and Club Penguin's games). By Flashpoint game id, in this order; Happy
 * Wheels is its HTML5 release by its own makers.
 */
val FEATURED_GAMES = listOf(
    "279b23ca-c7f3-4f56-a02f-3752ec7c5c5d", // Papa's Pizzeria
    "92ba2d91-e041-2bd4-49ea-21758df711ff", // Papa's Freezeria
    "f55d6576-1aee-414a-bd1e-b7678d697dcf", // Papa's Burgeria
    "6a7c068c-71bc-93f8-0c2b-708bf910359a", // Papa's Taco Mia!
    "9525910d-72b8-4e84-b668-43a267e00d9c", // Papa's Pancakeria
    "c3ab2546-a7b0-89b2-82de-4044d61e1cbd", // Papa's Wingeria
    "92781aea-6ad0-2bdb-a963-7131c49b7d07", // Papa's Hot Doggeria
    "de163cff-9dbe-fcbb-164c-53f5d6873fad", // Papa's Cupcakeria
    "2ecf56d6-c5e4-a801-bc7f-60374ba1a051", // Papa's Pastaria
    "938f4383-8c92-8e8d-511b-004b5c69999d", // Papa's Donuteria
    "617ca7f3-1cff-3f0c-5b53-07498b3b28d8", // Papa's Cheeseria
    "a8707c0f-6aac-4c94-9d8a-cc397c97cc88", // Papa's Bakeria
    "b9a8dbb9-0cd7-434b-b226-13dc9dd07b49", // Papa's Sushiria
    "1e903a30-5c37-15bb-8e5e-6fea5a8103f2", // Papa's Scooperia
    "13804696-e07e-374f-5b14-52dcf7bb27ec", // Gravity Guy
    "51240ae7-1fa8-4ee7-8d64-9dbf5019eab8", // Poptropica (AS2)
    "83e1b5e7-4282-4bbd-868e-dcfa965e4abf", // Poptropica
    "8cbb824f-73f1-49fe-9390-79e2295e4598", // The Fancy Pants Adventures: World 1
    "62a45085-51cd-443c-af15-a85ce7b06574", // The Fancy Pants Adventures: World 2
    "c5d2c57f-6ab9-481c-9436-901ad7528500", // The Fancy Pants Adventures: World 3
    "1227d57e-1ff7-48d6-bbb3-82649b192ee1", // The Fancy Pants Adventures: World 4 part 1
    "e4a2f2f5-ffb4-4a5e-9a91-d95a39afd612", // The Fancy Pants Adventures: World 4 part 2
    "1df51450-0aad-4e8a-8f33-0c1f4415b6ed", // The Fancy Pants Adventures: World 4 Part 3
    "16b04977-f714-4239-b343-b759e16a33af", // Super Mario 63
    "aab8ff04-e593-47c9-8186-a9e7c72f668e", // Ultimate Flash Sonic
    "06042f5e-fd62-482d-a222-46b56e81ac5b", // Ninja Painter
    "ea7ece99-dc71-4f82-9041-1f34919512ae", // Ninja Painter 2
    "c47c045e-f1c5-4f16-befb-98aaf80cb920", // Duck Life
    "21ed7be1-1eac-4b62-adb6-0a88ae1fa2ac", // Duck Life 2: World Champion
    "37011093-bd46-4385-98d1-4cda53724620", // Duck Life 3: Evolution
    "e41bbfa1-1de6-41e8-81ff-a1012d00143c", // Duck Life 4
    "d4b83357-fcdf-9166-783c-1f0339c5afcb", // Duck Life: Treasure Hunt
    "284d232b-180c-4329-8ca6-3a5f2d9c679f", // Breaking the Bank
    "600f6a00-e304-418d-a4ce-bfa611109d48", // Escaping the Prison
    "ea56315b-1144-41fe-9925-4bce200ff76c", // Stealing the Diamond
    "50fab4cd-5e35-41fe-b370-5b61ef247885", // Infiltrating the Airship
    "1aeb3e51-7601-4fc5-a3a2-a3498a6a824d", // Fleeing the Complex
    "b448cbec-8a0e-4abf-96cf-4a12b41b6818", // Happy Wheels
    "f74effdd-39d2-413d-93ff-68c912f6dd77", // Run
    "371749ad-fbb1-491e-8037-5953e1d46477", // Run 2
    "e75483c1-e4cc-44d1-95a6-a85c16b057b0", // Run 3
    "e0059b62-ad8a-4d5e-bdc9-c59acffc954e", // Fireboy & Watergirl in The Forest Temple
    "a3cfa640-3f47-4e18-ba44-e5083db99d6b", // Fireboy & Watergirl 2 in The Light Temple
    "eb9a27b0-834a-4982-af0e-f30aebb4eed3", // Fireboy & Watergirl 3 in The Ice Temple
    "aedea00b-4e68-446d-b847-cad06d9d49b0", // Fireboy and Watergirl 4: In The Crystal Temple
    "462cb01d-38d9-4f75-98d1-01794c387eec", // Fireboy & Watergirl: Elements
    "d1ca652b-06a3-4da0-9027-40f2e9dc15dd", // Fireboy & Watergirl: Fairy Tales
    "11ee16a3-dadb-4d54-84dd-a3dcf124ee1d", // Learn to Fly
    "891ac9f2-93da-4141-bd51-6a61c4aeea38", // Learn 2 Fly
    "95b9df8c-59ce-45d1-ae0b-696e190d9e14", // Learn to Fly 3
    "3a4abbc9-0dba-4c8b-b6db-cbb484f3acfc", // Jetpack Joyride
    "dbcc7433-ecfd-c820-8263-e60cd4eb0a1a", // Angry Birds
    "da6b3cb4-78a7-e998-fcf4-cb26a7950754", // Plants vs. Zombies
    "e11fa6dd-ab4e-41e5-ba87-cdaa63f6b320", // Plants vs Zombies 2
    "f2c3eb37-444a-4f1f-9f24-e4f9442ddcb7", // Bits & Bolts
    "e33d57cc-8bb2-406e-a21c-b282d6e8f0ee", // Blaster Game
    "05383ad0-70c4-4ef4-99bc-b807b18a4866", // CLUB PENGUIN ISLAND - THE MEDIEVAL TEXT ADVENTURE
    "1102e974-3f51-4525-9bc9-0ae08b74307f", // Chip Maze
    "6000ff85-4ddf-4ea9-969b-cf7c30a47c5f", // Circuit Match
    "9f56ae88-c340-4efc-9200-86a5d18c5cef", // Club Penguin Disco Puzzle
    "4fbb7e1b-0b37-4d66-b9c7-372082acf6be", // Club Penguin Halloween Party Pipe Game
    "4b1063fa-e076-418e-9c7c-1b4e0f073028", // Club Penguin Inside Out Party: Sliding Block Game
    "a3cc8df8-2bfe-ee39-1892-1c004fe60d35", // Club Penguin ThinIcetroBarrier
    "27dadcfa-e45d-4da7-8219-296e659719c2", // Code Break
    "91d19603-9691-4434-bded-60078f9847bb", // Code Decrypt
    "f340ead3-5054-451e-aa10-c51f0193e8c5", // DJ3000
    "c6333248-ccf9-418a-95c7-045c4d8060b1", // Dance Contest
    "ae3a677c-3ae8-4432-9044-dda9325bf2bd", // Firewall
    "2e471427-f7bc-4967-908a-4e3805d426fb", // Grid Command
    "70378827-e66d-460e-82df-c318f296fd2c", // Hydro-Hopper
    "ca5b8c85-22c1-48f9-b96d-43d5652f19a0", // Ice Fishing
    "a26460a9-a081-464d-be50-2d94d19db7df", // Jet Pack Adventure
    "f6c86153-7cd3-324b-fa54-787de16cd477", // Puffle Bowling
    "d449aee2-8163-4499-ae82-a27e873b04e7", // Puffle Chase
    "cd180375-2796-4b61-9694-c465676c01fc", // Puffle Launch
    "c4ca4ed5-c488-43b9-899f-4f3e10e40484", // Puffle Rescue
    "76a20e79-0d6c-4ee3-965e-036f9d74b557", // Puffle Shuffle
    "0afafdfa-b6c9-4e9f-bc0a-fa4d99711965", // Pufflescape
    "85b9c153-0ff0-4aae-9121-f5244a567a8e", // Scare Challenge
    "82af9976-d04b-4566-997a-8cc413a484e2", // Scorn Battle
    "b5558d7b-7c50-44b5-97e8-d066e11a5fa4", // Smoothie Smash
    "adabe1c7-f63b-4152-a577-30103b8aa607", // Solar System
    "de2724e3-0f7b-4f3f-bef1-bd72729eb16c", // Super Hero Bounce
    "916f6224-0744-e8ca-ef53-31154609e3c4", // System Defender
    "09b1a0a3-db9a-4ab6-b1dd-acd88cf8e823", // Thin Ice
    "6d3c876d-b79c-4762-b257-766ae2db032d", // Tumblers
    "d90a7aae-26b6-4f4a-ace5-4f325756381e", // X-Wing Pilot Game
    "6d488e80-50d7-c28d-a7ce-ef40a5b6a69e", // Avalanche Rescue
    "254ffd18-995f-e908-0ef8-b666f2ae583e", // Case of the Missing Coins
    "eca0cc01-f180-7c6e-2764-eb45aef1ca69", // Case of the Missing Puffles
    "20d09779-cee9-4578-b9cb-2285c028e050", // Clockwork Repairs
    "0141bfba-7cf3-00b7-4882-221e71609331", // G's Secret Mission
    "15a01e27-e23f-4661-8cd8-4d4fbdf00c88", // Mysterious Tremors
    "b9a45232-cd0c-48e4-895d-0311b32ea7ea", // Operation: Spy & Seek
    "8233bda1-6307-4436-8e2f-9232f183577e", // Questions for a Crab
    "bc7b622d-436e-d9c5-7c62-40ebfc04c3a5", // Secret of the Fur
    "cfa14c4b-6b3d-47f7-a1a1-b2fe2c9cf665", // The Veggie Villain
    "20a49e40-0a41-4406-8fe8-be694d172123", // Waddle Squad
)
