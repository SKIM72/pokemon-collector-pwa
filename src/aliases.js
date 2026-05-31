const ALIAS_GROUPS = [
  ["피카츄", "ピカチュウ", "Pikachu"],
  ["리자몽", "リザードン", "Charizard"],
  ["파이리", "ヒトカゲ", "Charmander"],
  ["리자드", "リザード", "Charmeleon"],
  ["이상해씨", "フシギダネ", "Bulbasaur"],
  ["이상해풀", "フシギソウ", "Ivysaur"],
  ["이상해꽃", "フシギバナ", "Venusaur"],
  ["꼬부기", "ゼニガメ", "Squirtle"],
  ["어니부기", "カメール", "Wartortle"],
  ["거북왕", "カメックス", "Blastoise"],
  ["뮤", "ミュウ", "Mew"],
  ["뮤츠", "ミュウツー", "Mewtwo"],
  ["이브이", "イーブイ", "Eevee"],
  ["샤미드", "シャワーズ", "Vaporeon"],
  ["쥬피썬더", "サンダース", "Jolteon"],
  ["부스터", "ブースター", "Flareon"],
  ["에브이", "エーフィ", "Espeon"],
  ["블래키", "ブラッキー", "Umbreon"],
  ["리피아", "リーフィア", "Leafeon"],
  ["글레이시아", "グレイシア", "Glaceon"],
  ["님피아", "ニンフィア", "Sylveon"],
  ["루카리오", "ルカリオ", "Lucario"],
  ["가디안", "サーナイト", "Gardevoir"],
  ["팬텀", "ゲンガー", "Gengar"],
  ["망나뇽", "カイリュー", "Dragonite"],
  ["잠만보", "カビゴン", "Snorlax"],
  ["갸라도스", "ギャラドス", "Gyarados"],
  ["라프라스", "ラプラス", "Lapras"],
  ["레쿠쟈", "レックウザ", "Rayquaza"],
  ["가이오가", "カイオーガ", "Kyogre"],
  ["그란돈", "グラードン", "Groudon"],
  ["아르세우스", "アルセウス", "Arceus"],
  ["기라티나", "ギラティナ", "Giratina"],
  ["디아루가", "ディアルガ", "Dialga"],
  ["펄기아", "パルキア", "Palkia"],
  ["다크라이", "ダークライ", "Darkrai"],
  ["세레비", "セレビィ", "Celebi"],
  ["지라치", "ジラーチ", "Jirachi"],
  ["테라파고스", "テラパゴス", "Terapagos"],
  ["오거폰", "オーガポン", "Ogerpon"],
  ["마스카나", "マスカーニャ", "Meowscarada"],
  ["라우드본", "ラウドボーン", "Skeledirge"],
  ["웨이니발", "ウェーニバル", "Quaquaval"],
  ["코라이돈", "コライドン", "Koraidon"],
  ["미라이돈", "ミライドン", "Miraidon"],
];

const SUFFIXES = ["ex", "EX", "V", "VMAX", "VSTAR", "GX"];

export function expandQueryAliases(query) {
  const normalized = normalize(query);
  const variants = new Set([query.trim()]);

  for (const group of ALIAS_GROUPS) {
    const matched = group.some((name) => normalized.includes(normalize(name)));
    if (!matched) continue;

    const suffix = detectSuffix(query);
    group.forEach((name) => {
      variants.add(name);
      if (suffix) {
        variants.add(`${name}${suffix}`);
        variants.add(`${name} ${suffix}`);
      }
    });
  }

  return [...variants].filter(Boolean);
}

export function getAliasLocalizations(value) {
  const normalized = normalize(value);
  const group = ALIAS_GROUPS.find((names) => names.some((name) => normalized.includes(normalize(name))));
  if (!group) return {};

  const suffix = detectSuffix(value);
  return {
    ko: `${group[0]}${suffix}`,
    ja: `${group[1]}${suffix}`,
    en: suffix ? `${group[2]} ${suffix}` : group[2],
  };
}

function detectSuffix(query) {
  const compact = query.replace(/\s+/g, "");
  return SUFFIXES.find((suffix) => compact.toLowerCase().endsWith(suffix.toLowerCase())) || "";
}

function normalize(value) {
  return String(value || "")
    .normalize("NFKC")
    .replace(/\s+/g, "")
    .toLowerCase();
}
